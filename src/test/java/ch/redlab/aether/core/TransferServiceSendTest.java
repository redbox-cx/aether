package ch.redlab.aether.core;

import ch.redlab.aether.crypto.CryptoService;
import ch.redlab.aether.crypto.DefaultCryptoService;
import ch.redlab.aether.model.Chunk;
import ch.redlab.aether.model.Device;
import ch.redlab.aether.model.Direction;
import ch.redlab.aether.model.FileInfo;
import ch.redlab.aether.model.SessionKey;
import ch.redlab.aether.model.Transfer;
import ch.redlab.aether.model.TransferStatus;
import ch.redlab.aether.network.TcpConnection;
import ch.redlab.aether.network.TcpServer;
import ch.redlab.aether.protocol.ErrorCode;
import ch.redlab.aether.protocol.Message;
import ch.redlab.aether.protocol.MessageChannel;
import ch.redlab.aether.protocol.ProtocolConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Sending side of {@link TransferService}. Most tests run a complete transfer between two
 * services on localhost with the real {@link DefaultCryptoService}; the others play the
 * receiver by hand to check single messages.
 */
@Timeout(60)
class TransferServiceSendTest {

    private static final int CHUNK = ProtocolConstants.CHUNK_SIZE;
    private static final Duration WAIT = Duration.ofSeconds(5);

    @TempDir
    Path senderDir;

    @TempDir
    Path receiverDir;

    private final CryptoService crypto = new DefaultCryptoService();
    private final List<TcpServer> servers = new ArrayList<>();

    private TransferService sender;
    private TransferService receiver;
    private volatile Consumer<IncomingRequest> decide = IncomingRequest::accept;
    private volatile Transfer received;

    @AfterEach
    void stopServers() {
        servers.forEach(TcpServer::close);
    }

    // ------------------------------------------------------------------ end to end, real crypto

    @ParameterizedTest
    @ValueSource(ints = {0, 1, CHUNK, CHUNK + 1, 5 * 1024 * 1024 + 3})
    void transfersFileEncryptedAndUnchanged(int size) throws Exception {
        Device target = startBoth(Duration.ofSeconds(5));
        Path file = file("data.bin", randomBytes(size));

        Transfer transfer = sender.send(target, file, FileInfo.fromFile(file));

        assertEquals(Direction.OUTGOING, transfer.getDirection());
        assertEquals(TransferStatus.COMPLETED, awaitFinal(transfer));
        assertEquals(TransferStatus.COMPLETED, awaitFinal(awaitReceived()));
        assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(receiverDir.resolve("data.bin")));
        assertEquals(size, transfer.getBytesTransferred());
        assertEquals(1.0, transfer.getProgress());
    }

    @Test
    void bothSidesShowTheSameSecurityCode() throws Exception {
        Device target = startBoth(Duration.ofSeconds(5));
        Path file = file("a.txt", randomBytes(10));

        Transfer transfer = sender.send(target, file, FileInfo.fromFile(file));
        awaitFinal(transfer);

        String code = transfer.getSecurityCode();
        assertNotNull(code);
        assertTrue(code.matches("\\d{6}"), code);
        assertEquals(code, awaitReceived().getSecurityCode());
    }

    @Test
    void receiverDeclines() throws Exception {
        decide = IncomingRequest::reject;
        Device target = startBoth(Duration.ofSeconds(5));
        Path file = file("a.txt", randomBytes(10));

        Transfer transfer = sender.send(target, file, FileInfo.fromFile(file));

        assertEquals(TransferStatus.REJECTED, awaitFinal(transfer));
        assertEquals(Message.TransferReject.USER, transfer.getMessage());
        assertEquals(List.of(), filesIn(receiverDir));
    }

    @Test
    void receiverDoesNotAnswer() throws Exception {
        decide = request -> { };
        Device target = startBoth(Duration.ofMillis(300));
        Path file = file("a.txt", randomBytes(10));

        Transfer transfer = sender.send(target, file, FileInfo.fromFile(file));

        assertEquals(TransferStatus.REJECTED, awaitFinal(transfer));
        assertEquals(Message.TransferReject.TIMEOUT, transfer.getMessage());
    }

    @Test
    void senderCancelsWhileSending() throws Exception {
        Device target = startBoth(Duration.ofSeconds(5));
        cancelOnFirstProgress(sender, Direction.OUTGOING);
        Path file = file("big.bin", randomBytes(20 * 1024 * 1024));

        Transfer transfer = sender.send(target, file, FileInfo.fromFile(file));

        assertEquals(TransferStatus.CANCELLED, awaitFinal(transfer));
        assertEquals(TransferStatus.CANCELLED, awaitFinal(awaitReceived()));
        assertEquals(List.of(), filesIn(receiverDir));
    }

    @Test
    void receiverCancelsWhileReceiving() throws Exception {
        Device target = startBoth(Duration.ofSeconds(5));
        cancelOnFirstProgress(receiver, Direction.INCOMING);
        Path file = file("big.bin", randomBytes(20 * 1024 * 1024));

        Transfer transfer = sender.send(target, file, FileInfo.fromFile(file));

        assertEquals(TransferStatus.CANCELLED, awaitFinal(awaitReceived()));
        assertEquals(TransferStatus.CANCELLED, awaitFinal(transfer));
        assertEquals(List.of(), filesIn(receiverDir));
    }

    @Test
    void senderCancelsWhileWaitingForTheDecision() throws Exception {
        CompletableFuture<IncomingRequest> pending = new CompletableFuture<>();
        decide = pending::complete;
        Device target = startBoth(Duration.ofSeconds(5));
        Path file = file("a.txt", randomBytes(10));

        Transfer transfer = sender.send(target, file, FileInfo.fromFile(file));
        pending.get(5, TimeUnit.SECONDS);
        sender.cancel(transfer.getId());

        assertEquals(TransferStatus.CANCELLED, awaitFinal(transfer));
    }

    @Test
    void fileChangedAfterItWasPicked() throws Exception {
        Device target = startBoth(Duration.ofSeconds(5));
        Path file = file("a.txt", randomBytes(100));
        FileInfo info = FileInfo.fromFile(file);
        Files.write(file, randomBytes(50));

        Transfer transfer = sender.send(target, file, info);

        assertEquals(TransferStatus.FAILED, awaitFinal(transfer));
        assertEquals(TransferStatus.FAILED, awaitFinal(awaitReceived()));
        assertEquals(List.of(), filesIn(receiverDir));
    }

    @Test
    void nobodyListening() throws Exception {
        sender = new TransferService(new Settings("sender-id", "Sender", senderDir), crypto, timeouts(Duration.ofSeconds(5)));
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Device target = new Device("x", "Nobody", InetAddress.getLoopbackAddress(), closedPort, Instant.now());
        Path file = file("a.txt", randomBytes(10));

        Transfer transfer = sender.send(target, file, FileInfo.fromFile(file));

        assertEquals(TransferStatus.FAILED, awaitFinal(transfer));
        assertEquals(ErrorCode.IO_ERROR.name(), transfer.getMessage());
    }

    // ------------------------------------------------------------------ receiver played by hand

    @Test
    void chunksOnTheWireAreEncrypted() throws Exception {
        byte[] content = "AETHER-TESTSTRING-4711 ".repeat(10_000).getBytes(StandardCharsets.UTF_8);
        CompletableFuture<List<Message.FileChunk>> chunks = new CompletableFuture<>();
        CompletableFuture<SessionKey> receiverKey = new CompletableFuture<>();

        Device target = startScriptedReceiver(channel -> {
            Message.TransferRequest request = (Message.TransferRequest) channel.receive(WAIT);
            KeyPair keys = crypto.newKeyPair();
            SessionKey session = crypto.deriveSessionKey(keys, request.senderPublicKey(), request.transferId());
            receiverKey.complete(session);
            channel.send(new Message.KeyExchange(request.transferId(), keys.getPublic().getEncoded()));
            channel.send(new Message.TransferAccept(request.transferId()));
            List<Message.FileChunk> list = new ArrayList<>();
            Message message;
            while ((message = channel.receive(WAIT)) instanceof Message.FileChunk chunk) {
                list.add(chunk);
            }
            chunks.complete(list);
            channel.send(new Message.TransferAck(request.transferId(), Message.TransferAck.OK));
        });
        Path file = file("secret.txt", content);

        Transfer transfer = sender.send(target, file, FileInfo.fromFile(file));
        assertEquals(TransferStatus.COMPLETED, awaitFinal(transfer));

        List<Message.FileChunk> wire = chunks.get(5, TimeUnit.SECONDS);
        SessionKey session = receiverKey.get(5, TimeUnit.SECONDS);
        assertEquals(session.securityCode(), transfer.getSecurityCode());

        byte[] marker = "AETHER-TESTSTRING".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        Set<String> ivs = new HashSet<>();
        for (Message.FileChunk chunk : wire) {
            assertFalse(contains(chunk.ciphertext(), marker), "Plaintext visible in chunk " + chunk.chunkIndex());
            assertTrue(ivs.add(java.util.HexFormat.of().formatHex(chunk.iv())), "IV used twice");
            decrypted.write(crypto.decrypt(session, new Chunk(chunk.chunkIndex(), chunk.iv(), chunk.ciphertext())));
        }
        assertArrayEquals(content, decrypted.toByteArray());
    }

    @Test
    void requestCarriesNameSizeAndChecksum() throws Exception {
        CompletableFuture<Message.TransferRequest> seen = new CompletableFuture<>();
        Device target = startScriptedReceiver(channel -> {
            Message.TransferRequest request = (Message.TransferRequest) channel.receive(WAIT);
            seen.complete(request);
            channel.send(new Message.TransferReject(request.transferId(), Message.TransferReject.USER));
        });
        Path file = file("report.pdf", randomBytes(1234));
        FileInfo info = FileInfo.fromFile(file);

        Transfer transfer = sender.send(target, file, info);

        Message.TransferRequest request = seen.get(5, TimeUnit.SECONDS);
        assertEquals(transfer.getId(), request.transferId());
        assertEquals("Sender", request.senderName());
        assertEquals("report.pdf", request.fileName());
        assertEquals(1234, request.fileSize());
        assertArrayEquals(info.sha256(), request.sha256());
        assertEquals(TransferStatus.REJECTED, awaitFinal(transfer));
    }

    @Test
    void noKeyExchangeInTime() throws Exception {
        CompletableFuture<Message> afterRequest = new CompletableFuture<>();
        Device target = startScriptedReceiver(channel -> {
            channel.receive(WAIT);
            afterRequest.complete(channel.receive(WAIT));
        });
        Path file = file("a.txt", randomBytes(10));

        Transfer transfer = sender.send(target, file, FileInfo.fromFile(file));

        assertEquals(TransferStatus.FAILED, awaitFinal(transfer));
        assertEquals(ErrorCode.TIMEOUT.name(), transfer.getMessage());
        Message.Error error = assertInstanceOf(Message.Error.class, afterRequest.get(5, TimeUnit.SECONDS));
        assertEquals(ErrorCode.TIMEOUT, error.code());
    }

    @Test
    void errorInsteadOfKeyExchange() throws Exception {
        Device target = startScriptedReceiver(channel -> {
            channel.receive(WAIT);
            channel.send(new Message.Error(ErrorCode.IO_ERROR, "Invalid file name"));
        });
        Path file = file("a.txt", randomBytes(10));

        Transfer transfer = sender.send(target, file, FileInfo.fromFile(file));

        assertEquals(TransferStatus.FAILED, awaitFinal(transfer));
        assertEquals(ErrorCode.IO_ERROR.name(), transfer.getMessage());
    }

    @Test
    void receiverReportsWrongChecksum() throws Exception {
        Device target = startScriptedReceiver(channel -> {
            Message.TransferRequest request = (Message.TransferRequest) channel.receive(WAIT);
            KeyPair keys = crypto.newKeyPair();
            channel.send(new Message.KeyExchange(request.transferId(), keys.getPublic().getEncoded()));
            channel.send(new Message.TransferAccept(request.transferId()));
            while (channel.receive(WAIT) instanceof Message.FileChunk) {
                // Ignore the content.
            }
            channel.send(new Message.TransferAck(request.transferId(), Message.TransferAck.HASH_MISMATCH));
        });
        Path file = file("a.txt", randomBytes(10));

        Transfer transfer = sender.send(target, file, FileInfo.fromFile(file));

        assertEquals(TransferStatus.FAILED, awaitFinal(transfer));
        assertEquals(Message.TransferAck.HASH_MISMATCH, transfer.getMessage());
    }

    // ------------------------------------------------------------------ helpers

    private static TransferService.Timeouts timeouts(Duration decision) {
        return new TransferService.Timeouts(Duration.ofMillis(500), decision, Duration.ofSeconds(5));
    }

    /** Starts a real receiving service and the sending service; returns the receiver as device. */
    private Device startBoth(Duration decisionTimeout) throws IOException {
        receiver = new TransferService(new Settings("receiver-id", "Receiver", receiverDir), crypto, timeouts(decisionTimeout));
        receiver.addListener(new TransferListener() {
            @Override
            public void onIncomingRequest(IncomingRequest request) {
                received = request.getTransfer();
                decide.accept(request);
            }

            @Override
            public void onTransferUpdated(Transfer transfer) {
                received = transfer;
            }
        });
        sender = new TransferService(new Settings("sender-id", "Sender", senderDir), crypto, timeouts(decisionTimeout));
        return device(start(receiver::handleIncoming));
    }

    @FunctionalInterface
    private interface Script {
        void run(MessageChannel channel) throws Exception;
    }

    /** Starts the sending service and a receiver that follows the given script. */
    private Device startScriptedReceiver(Script script) throws IOException {
        sender = new TransferService(new Settings("sender-id", "Sender", senderDir), crypto, timeouts(Duration.ofSeconds(5)));
        return device(start(connection -> {
            try {
                script.run(new MessageChannel(connection));
            } catch (Exception e) {
                // The sender closed the connection; the assertions are on the sender side.
            }
        }));
    }

    private TcpServer start(Consumer<TcpConnection> handler) throws IOException {
        TcpServer server = new TcpServer(0, handler);
        server.start();
        servers.add(server);
        return server;
    }

    private static Device device(TcpServer server) {
        return new Device("receiver-id", "Receiver", InetAddress.getLoopbackAddress(), server.getPort(), Instant.now());
    }

    /** Cancels the transfer of the given direction as soon as the first bytes went through. */
    private static void cancelOnFirstProgress(TransferService service, Direction direction) {
        AtomicBoolean done = new AtomicBoolean();
        service.addListener(new TransferListener() {
            @Override
            public void onIncomingRequest(IncomingRequest request) {
            }

            @Override
            public void onTransferUpdated(Transfer transfer) {
                if (transfer.getDirection() == direction && transfer.getBytesTransferred() > 0
                        && done.compareAndSet(false, true)) {
                    service.cancel(transfer.getId());
                }
            }
        });
    }

    private Transfer awaitReceived() throws InterruptedException {
        long end = System.nanoTime() + WAIT.toNanos();
        while (received == null) {
            if (System.nanoTime() > end) {
                fail("Receiver never saw the transfer");
            }
            Thread.sleep(10);
        }
        return received;
    }

    private static TransferStatus awaitFinal(Transfer transfer) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!transfer.getStatus().isFinal()) {
            if (System.nanoTime() > end) {
                fail("Transfer still " + transfer.getStatus());
            }
            Thread.sleep(10);
        }
        return transfer.getStatus();
    }

    private Path file(String name, byte[] content) throws IOException {
        return Files.write(senderDir.resolve(name), content);
    }

    private static List<String> filesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static boolean contains(byte[] data, byte[] part) {
        outer:
        for (int i = 0; i <= data.length - part.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (data[i + j] != part[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return data;
    }
}
