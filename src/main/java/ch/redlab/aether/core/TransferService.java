package ch.redlab.aether.core;

import ch.redlab.aether.crypto.CryptoService;
import ch.redlab.aether.model.Chunk;
import ch.redlab.aether.model.Direction;
import ch.redlab.aether.model.FileInfo;
import ch.redlab.aether.model.SessionKey;
import ch.redlab.aether.model.Transfer;
import ch.redlab.aether.model.TransferStatus;
import ch.redlab.aether.network.TcpConnection;
import ch.redlab.aether.protocol.ErrorCode;
import ch.redlab.aether.protocol.Message;
import ch.redlab.aether.protocol.MessageChannel;
import ch.redlab.aether.protocol.ProtocolConstants;
import ch.redlab.aether.protocol.ProtocolException;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs file transfers according to the message sequence of ATP v1 (Konzeptbericht 4.2).
 * <p>
 * Receiving side (UC3): {@link #handleIncoming} is the handler of the {@code TcpServer} and runs
 * on the thread of the connection. The sending side follows in a later step.
 * <p>
 * Listener callbacks arrive on background threads.
 */
public class TransferService {

    private static final System.Logger LOG = System.getLogger(TransferService.class.getName());

    /** Time limits of a transfer; tests use shorter values. */
    public record Timeouts(Duration keyExchange, Duration decision, Duration idle) {
        public static final Timeouts DEFAULT = new Timeouts(
                ProtocolConstants.KEY_EXCHANGE_TIMEOUT,
                ProtocolConstants.DECISION_TIMEOUT,
                ProtocolConstants.IDLE_TIMEOUT);
    }

    private final Settings settings;
    private final CryptoService crypto;
    private final Timeouts timeouts;
    private final List<TransferListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();

    public TransferService(Settings settings, CryptoService crypto) {
        this(settings, crypto, Timeouts.DEFAULT);
    }

    public TransferService(Settings settings, CryptoService crypto, Timeouts timeouts) {
        this.settings = settings;
        this.crypto = crypto;
        this.timeouts = timeouts;
    }

    public void addListener(TransferListener listener) {
        listeners.add(listener);
    }

    /**
     * Cancels a transfer. While the user has not decided yet, this declines the request.
     * Otherwise the peer gets ERROR (CANCELLED) and the connection is closed.
     */
    public void cancel(UUID transferId) {
        Active current = active.get(transferId);
        if (current == null || current.transfer.getStatus().isFinal()) {
            return;
        }
        if (current.request != null && current.transfer.getStatus() == TransferStatus.REQUESTED) {
            current.request.reject();
            return;
        }
        // The thread running the transfer sees the closed channel, cleans up and reports CANCELLED.
        current.cancelled = true;
        sendQuietly(current.channel, new Message.Error(ErrorCode.CANCELLED, "Cancelled by user"));
        current.channel.close();
    }

    // ------------------------------------------------------------------ receiving side

    /** Handles one incoming connection from start to end. Returns when the transfer is over. */
    public void handleIncoming(TcpConnection connection) {
        MessageChannel channel = new MessageChannel(connection);
        Message first;
        try {
            first = channel.receive(timeouts.idle());
        } catch (ProtocolException e) {
            sendQuietly(channel, new Message.Error(e.getCode(), e.getMessage()));
            return;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "No transfer request from " + channel.getRemoteAddress() + ": " + e);
            return;
        }
        if (!(first instanceof Message.TransferRequest request)) {
            sendQuietly(channel, new Message.Error(ErrorCode.IO_ERROR, "Expected TRANSFER_REQUEST, got " + first.type()));
            return;
        }
        receive(channel, request);
    }

    private void receive(MessageChannel channel, Message.TransferRequest request) {
        UUID id = request.transferId();
        Optional<String> name = FileNames.sanitize(request.fileName());
        if (name.isEmpty()) {
            sendQuietly(channel, new Message.Error(ErrorCode.IO_ERROR, "Invalid file name"));
            return;
        }
        Transfer transfer = new Transfer(id, Direction.INCOMING, request.senderName(),
                new FileInfo(name.get(), request.fileSize(), request.sha256()));
        IncomingRequest decision = new IncomingRequest(transfer);
        Active current = new Active(transfer, channel, decision);
        if (active.putIfAbsent(id, current) != null) {
            sendQuietly(channel, new Message.Error(ErrorCode.IO_ERROR, "Transfer id already in use"));
            return;
        }

        try {
            // Answer with our key right away, so both sides can show the security code (UC4).
            KeyPair keys = crypto.newKeyPair();
            SessionKey session = crypto.deriveSessionKey(keys, request.senderPublicKey(), id);
            channel.send(new Message.KeyExchange(id, keys.getPublic().getEncoded()));
            transfer.setSecurityCode(session.securityCode());

            if (!askUser(channel, decision)) {
                return;
            }
            Outcome outcome = receiveChunks(channel, request, transfer, session);
            finish(transfer, outcome.status(), outcome.message());
        } catch (ProtocolException e) {
            sendQuietly(channel, new Message.Error(e.getCode(), e.getMessage()));
            fail(current, e.getCode());
        } catch (GeneralSecurityException e) {
            sendQuietly(channel, new Message.Error(ErrorCode.DECRYPT_FAILED, "Decryption failed"));
            fail(current, ErrorCode.DECRYPT_FAILED);
        } catch (IOException e) {
            // Connection lost, or closed by cancel().
            fail(current, ErrorCode.IO_ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(current, ErrorCode.IO_ERROR);
        } finally {
            active.remove(id);
            channel.close();
        }
    }

    /** Shows the dialog and sends the answer. Returns true if the user accepted. */
    private boolean askUser(MessageChannel channel, IncomingRequest decision)
            throws IOException, InterruptedException {
        Transfer transfer = decision.getTransfer();
        for (TransferListener listener : listeners) {
            try {
                listener.onIncomingRequest(decision);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Listener failed", e);
            }
        }

        Boolean accepted = decision.await(timeouts.decision());
        if (accepted == null || !accepted) {
            String reason = accepted == null ? Message.TransferReject.TIMEOUT : Message.TransferReject.USER;
            channel.send(new Message.TransferReject(transfer.getId(), reason));
            finish(transfer, TransferStatus.REJECTED, reason);
            return false;
        }
        channel.send(new Message.TransferAccept(transfer.getId()));
        update(transfer, TransferStatus.ACCEPTED);
        return true;
    }

    /**
     * Writes the decrypted chunks into a temporary file in the download folder and only
     * gives it its real name once the SHA-256 checksum is correct. The temporary file is
     * deleted in every other case (TC07), before the final status is reported.
     */
    private Outcome receiveChunks(MessageChannel channel, Message.TransferRequest request, Transfer transfer,
                               SessionKey session) throws IOException, GeneralSecurityException {
        Path folder = settings.getDownloadDir();
        Files.createDirectories(folder);
        Path part = Files.createTempFile(folder, ".aether-", ".part");
        Path saved = null;
        boolean acknowledged = false;
        try {
            MessageDigest sha = sha256();
            long size = request.fileSize();
            long received = 0;
            long nextIndex = 0;
            update(transfer, TransferStatus.RUNNING);

            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(part))) {
                while (true) {
                    Message message = channel.receive(timeouts.idle());
                    if (message instanceof Message.FileChunk chunk) {
                        if (chunk.chunkIndex() != nextIndex) {
                            throw new ProtocolException(ErrorCode.DECRYPT_FAILED,
                                    "Expected chunk " + nextIndex + ", got " + chunk.chunkIndex());
                        }
                        byte[] plain = crypto.decrypt(session, new Chunk(chunk.chunkIndex(), chunk.iv(), chunk.ciphertext()));
                        if (plain.length > size - received) {
                            throw new ProtocolException(ErrorCode.IO_ERROR, "More data than announced");
                        }
                        out.write(plain);
                        sha.update(plain);
                        received += plain.length;
                        nextIndex++;
                        transfer.setBytesTransferred(received);
                        fire(transfer);
                    } else if (message instanceof Message.TransferComplete complete) {
                        if (!complete.transferId().equals(transfer.getId())
                                || complete.chunkCount() != nextIndex || received != size) {
                            throw new ProtocolException(ErrorCode.IO_ERROR, "Transfer incomplete");
                        }
                        break;
                    } else if (message instanceof Message.Error error) {
                        TransferStatus status = error.code() == ErrorCode.CANCELLED
                                ? TransferStatus.CANCELLED : TransferStatus.FAILED;
                        return new Outcome(status, error.code().name());
                    } else {
                        throw new ProtocolException(ErrorCode.IO_ERROR, "Unexpected " + message.type());
                    }
                }
            }

            if (!MessageDigest.isEqual(sha.digest(), request.sha256())) {
                channel.send(new Message.TransferAck(transfer.getId(), Message.TransferAck.HASH_MISMATCH));
                return new Outcome(TransferStatus.FAILED, Message.TransferAck.HASH_MISMATCH);
            }
            saved = FileNames.moveToFreeName(part, folder, transfer.getFile().name());
            channel.send(new Message.TransferAck(transfer.getId(), Message.TransferAck.OK));
            acknowledged = true;
            transfer.setSavedFile(saved);
            return new Outcome(TransferStatus.COMPLETED, null);
        } finally {
            Files.deleteIfExists(part);
            if (saved != null && !acknowledged) {
                // The ACK could not be sent after the file was saved; do not keep a file the sender thinks failed.
                Files.deleteIfExists(saved);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Changes the status unless the transfer is already over, and notifies the listeners. */
    private void update(Transfer transfer, TransferStatus status) {
        synchronized (transfer) {
            if (transfer.getStatus().isFinal()) {
                return;
            }
            transfer.setStatus(status);
        }
        fire(transfer);
    }

    /** Ends a transfer that went wrong; CANCELLED if the user cancelled it, FAILED otherwise. */
    private void fail(Active current, ErrorCode code) {
        if (current.cancelled) {
            finish(current.transfer, TransferStatus.CANCELLED, ErrorCode.CANCELLED.name());
        } else {
            finish(current.transfer, TransferStatus.FAILED, code.name());
        }
    }

    /** Sets a final status once; later calls are ignored (e.g. FAILED after a CANCELLED). */
    private void finish(Transfer transfer, TransferStatus status, String message) {
        synchronized (transfer) {
            if (transfer.getStatus().isFinal()) {
                return;
            }
            transfer.setMessage(message);
            transfer.setStatus(status);
        }
        fire(transfer);
    }

    private void fire(Transfer transfer) {
        for (TransferListener listener : listeners) {
            try {
                listener.onTransferUpdated(transfer);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Listener failed", e);
            }
        }
    }

    private static void sendQuietly(MessageChannel channel, Message message) {
        try {
            channel.send(message);
        } catch (IOException ignored) {
            // The connection is already gone; nothing left to tell the peer.
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Final status of the receiving part and the detail for the UI. */
    private record Outcome(TransferStatus status, String message) {
    }

    /** A transfer that is currently running, so it can be cancelled. */
    private static final class Active {
        final Transfer transfer;
        final MessageChannel channel;
        final IncomingRequest request;
        volatile boolean cancelled;

        Active(Transfer transfer, MessageChannel channel, IncomingRequest request) {
            this.transfer = transfer;
            this.channel = channel;
            this.request = request;
        }
    }
}
