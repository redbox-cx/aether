package ch.redlab.aether.core;

import ch.redlab.aether.crypto.ChunkEncryptor;
import ch.redlab.aether.model.Chunk;
import ch.redlab.aether.model.Transfer;
import ch.redlab.aether.model.TransferStatus;
import ch.redlab.aether.network.TcpConnection;
import ch.redlab.aether.network.TcpServer;
import ch.redlab.aether.protocol.ErrorCode;
import ch.redlab.aether.protocol.Message;
import ch.redlab.aether.protocol.MessageChannel;
import ch.redlab.aether.protocol.ProtocolConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Receiving side of {@link TransferService}. The test plays the sender by hand over a real
 * TCP connection on localhost, so every message of the sequence can be checked.
 */
@Timeout(20)
class TransferServiceReceiveTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final int CHUNK = ProtocolConstants.CHUNK_SIZE;

    @TempDir
    Path downloads;

    private final FakeCryptoService crypto = new FakeCryptoService();
    private final BlockingQueue<IncomingRequest> requests = new LinkedBlockingQueue<>();
    private final BlockingQueue<TransferStatus> statuses = new LinkedBlockingQueue<>();
    private volatile Consumer<IncomingRequest> decide = IncomingRequest::accept;

    private TransferService service;
    private TcpServer server;

    @BeforeEach
    void startReceiver() throws IOException {
        startReceiver(Duration.ofSeconds(5));
    }

    private void startReceiver(Duration decisionTimeout) throws IOException {
        Settings settings = new Settings("receiver-id", "Receiver", downloads);
        TransferService.Timeouts timeouts = new TransferService.Timeouts(
                Duration.ofSeconds(2), decisionTimeout, Duration.ofSeconds(2));
        service = new TransferService(settings, crypto, timeouts);
        service.addListener(new TransferListener() {
            @Override
            public void onIncomingRequest(IncomingRequest request) {
                requests.add(request);
                decide.accept(request);
            }

            @Override
            public void onTransferUpdated(Transfer transfer) {
                statuses.add(transfer.getStatus());
            }
        });
        server = new TcpServer(0, service::handleIncoming);
        server.start();
    }

    @AfterEach
    void stopReceiver() {
        server.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, CHUNK, CHUNK + 1, 3 * CHUNK + 7})
    void receivesFileAndSavesItUnchanged(int size) throws Exception {
        byte[] content = randomBytes(size);

        try (MessageChannel sender = connect()) {
            UUID id = request(sender, "report.pdf", content);
            assertInstanceOf(Message.TransferAccept.class, sender.receive(WAIT));
            sendChunks(sender, content);
            sender.send(new Message.TransferComplete(id, chunkCount(size)));

            Message.TransferAck ack = assertInstanceOf(Message.TransferAck.class, sender.receive(WAIT));
            assertEquals(Message.TransferAck.OK, ack.status());
        }

        assertEquals(TransferStatus.COMPLETED, finalStatus());
        assertArrayEquals(content, Files.readAllBytes(downloads.resolve("report.pdf")));
        assertEquals(List.of("report.pdf"), filesInDownloads());
    }

    @Test
    void keyExchangeArrivesBeforeTheUserDecides() throws Exception {
        CountDownLatch decided = new CountDownLatch(1);
        decide = request -> Thread.ofVirtual().start(() -> {
            awaitQuietly(decided);
            request.accept();
        });

        try (MessageChannel sender = connect()) {
            UUID id = UUID.randomUUID();
            sender.send(transferRequest(id, "a.txt", new byte[0]));
            Message.KeyExchange keys = assertInstanceOf(Message.KeyExchange.class, sender.receive(WAIT));
            assertEquals(id, keys.transferId());
            assertTrue(keys.receiverPublicKey().length > 0);

            IncomingRequest shown = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(shown);
            assertEquals("Sender", shown.getTransfer().getPeerName());
            assertEquals("a.txt", shown.getTransfer().getFile().name());
            assertEquals(FakeCryptoService.SECURITY_CODE, shown.getTransfer().getSecurityCode());

            decided.countDown();
            assertInstanceOf(Message.TransferAccept.class, sender.receive(WAIT));
        }
    }

    @Test
    void userDeclines() throws Exception {
        decide = IncomingRequest::reject;

        try (MessageChannel sender = connect()) {
            request(sender, "a.txt", randomBytes(10));
            Message.TransferReject reject = assertInstanceOf(Message.TransferReject.class, sender.receive(WAIT));
            assertEquals(Message.TransferReject.USER, reject.reason());
        }

        assertEquals(TransferStatus.REJECTED, finalStatus());
        assertEquals(List.of(), filesInDownloads());
    }

    @Test
    void noAnswerDeclinesAfterTimeout() throws Exception {
        server.close();
        startReceiver(Duration.ofMillis(300));
        decide = request -> { };

        try (MessageChannel sender = connect()) {
            request(sender, "a.txt", randomBytes(10));
            Message.TransferReject reject = assertInstanceOf(Message.TransferReject.class, sender.receive(WAIT));
            assertEquals(Message.TransferReject.TIMEOUT, reject.reason());
        }

        assertEquals(TransferStatus.REJECTED, finalStatus());
    }

    @Test
    void wrongChecksumIsReportedAndFileDiscarded() throws Exception {
        byte[] announced = randomBytes(1000);
        byte[] sent = announced.clone();
        sent[500] ^= 1;

        try (MessageChannel sender = connect()) {
            UUID id = request(sender, "a.txt", announced);
            assertInstanceOf(Message.TransferAccept.class, sender.receive(WAIT));
            sendChunks(sender, sent);
            sender.send(new Message.TransferComplete(id, 1));

            Message.TransferAck ack = assertInstanceOf(Message.TransferAck.class, sender.receive(WAIT));
            assertEquals(Message.TransferAck.HASH_MISMATCH, ack.status());
        }

        assertEquals(TransferStatus.FAILED, finalStatus());
        assertEquals(List.of(), filesInDownloads());
    }

    @Test
    void tamperedChunkAbortsWithDecryptFailed() throws Exception {
        byte[] content = randomBytes(2 * CHUNK);

        try (MessageChannel sender = connect()) {
            request(sender, "a.txt", content);
            assertInstanceOf(Message.TransferAccept.class, sender.receive(WAIT));
            ChunkEncryptor encryptor = crypto.newEncryptor(null);
            sender.send(toMessage(encryptor.encrypt(0, content, CHUNK)));
            Chunk second = encryptor.encrypt(1, java.util.Arrays.copyOfRange(content, CHUNK, 2 * CHUNK), CHUNK);
            second.ciphertext()[second.ciphertext().length - 1] = 1;
            sender.send(toMessage(second));

            assertErrorCode(ErrorCode.DECRYPT_FAILED, sender.receive(WAIT));
        }

        assertEquals(TransferStatus.FAILED, finalStatus());
        assertEquals(List.of(), filesInDownloads());
    }

    @Test
    void chunkOutOfOrderAbortsWithDecryptFailed() throws Exception {
        byte[] content = randomBytes(10);

        try (MessageChannel sender = connect()) {
            request(sender, "a.txt", content);
            assertInstanceOf(Message.TransferAccept.class, sender.receive(WAIT));
            sender.send(new Message.FileChunk(1, FakeCryptoService.iv(1), new byte[ProtocolConstants.TAG_SIZE + 10]));

            assertErrorCode(ErrorCode.DECRYPT_FAILED, sender.receive(WAIT));
        }

        assertEquals(List.of(), filesInDownloads());
    }

    @Test
    void moreDataThanAnnouncedIsRejected() throws Exception {
        byte[] announced = randomBytes(10);

        try (MessageChannel sender = connect()) {
            request(sender, "a.txt", announced);
            assertInstanceOf(Message.TransferAccept.class, sender.receive(WAIT));
            sendChunks(sender, randomBytes(11));

            assertErrorCode(ErrorCode.IO_ERROR, sender.receive(WAIT));
        }

        assertEquals(TransferStatus.FAILED, finalStatus());
        assertEquals(List.of(), filesInDownloads());
    }

    @Test
    void senderDisconnectingLeavesNoFile() throws Exception {
        byte[] content = randomBytes(3 * CHUNK);

        try (MessageChannel sender = connect()) {
            request(sender, "a.txt", content);
            assertInstanceOf(Message.TransferAccept.class, sender.receive(WAIT));
            sender.send(toMessage(crypto.newEncryptor(null).encrypt(0, content, CHUNK)));
        }

        assertEquals(TransferStatus.FAILED, finalStatus());
        assertEquals(List.of(), filesInDownloads());
    }

    @Test
    void senderCancelling() throws Exception {
        byte[] content = randomBytes(3 * CHUNK);

        try (MessageChannel sender = connect()) {
            request(sender, "a.txt", content);
            assertInstanceOf(Message.TransferAccept.class, sender.receive(WAIT));
            sender.send(toMessage(crypto.newEncryptor(null).encrypt(0, content, CHUNK)));
            sender.send(new Message.Error(ErrorCode.CANCELLED, "Cancelled by user"));

            assertEquals(TransferStatus.CANCELLED, finalStatus());
        }

        assertEquals(List.of(), filesInDownloads());
    }

    @Test
    void receiverCancelling() throws Exception {
        byte[] content = randomBytes(3 * CHUNK);

        try (MessageChannel sender = connect()) {
            UUID id = request(sender, "a.txt", content);
            assertInstanceOf(Message.TransferAccept.class, sender.receive(WAIT));
            sender.send(toMessage(crypto.newEncryptor(null).encrypt(0, content, CHUNK)));
            awaitStatus(TransferStatus.RUNNING);

            service.cancel(id);

            assertErrorCode(ErrorCode.CANCELLED, sender.receive(WAIT));
            assertEquals(TransferStatus.CANCELLED, finalStatus());
        }

        assertEquals(List.of(), filesInDownloads());
    }

    @Test
    void cancellingBeforeTheDecisionDeclines() throws Exception {
        decide = request -> service.cancel(request.getTransfer().getId());

        try (MessageChannel sender = connect()) {
            request(sender, "a.txt", randomBytes(10));
            Message.TransferReject reject = assertInstanceOf(Message.TransferReject.class, sender.receive(WAIT));
            assertEquals(Message.TransferReject.USER, reject.reason());
        }
    }

    @Test
    void existingFileIsNotOverwritten() throws Exception {
        Files.writeString(downloads.resolve("report.pdf"), "old");
        Files.writeString(downloads.resolve("report (1).pdf"), "old");
        byte[] content = randomBytes(100);

        receiveCompletely("report.pdf", content);

        assertEquals("old", Files.readString(downloads.resolve("report.pdf")));
        assertArrayEquals(content, Files.readAllBytes(downloads.resolve("report (2).pdf")));
    }

    @Test
    void directoryPartsOfTheNameAreIgnored() throws Exception {
        byte[] content = randomBytes(100);

        receiveCompletely("../../evil.txt", content);
        receiveCompletely("..\\..\\evil2.txt", content);

        assertEquals(List.of("evil.txt", "evil2.txt"), filesInDownloads());
        assertTrue(Files.notExists(downloads.getParent().resolve("evil.txt")));
    }

    @Test
    void unusableNameIsRejected() throws Exception {
        try (MessageChannel sender = connect()) {
            sender.send(transferRequest(UUID.randomUUID(), "..", new byte[0]));
            assertErrorCode(ErrorCode.IO_ERROR, sender.receive(WAIT));
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    void firstMessageMustBeARequest() throws Exception {
        try (MessageChannel sender = connect()) {
            sender.send(new Message.TransferAccept(UUID.randomUUID()));
            assertErrorCode(ErrorCode.IO_ERROR, sender.receive(WAIT));
        }
    }

    // ------------------------------------------------------------------ helpers

    private MessageChannel connect() throws IOException {
        return new MessageChannel(TcpConnection.connect(InetAddress.getLoopbackAddress(), server.getPort(), WAIT));
    }

    /** Sends TRANSFER_REQUEST and reads the KEY_EXCHANGE. */
    private UUID request(MessageChannel sender, String fileName, byte[] content) throws Exception {
        UUID id = UUID.randomUUID();
        sender.send(transferRequest(id, fileName, content));
        assertInstanceOf(Message.KeyExchange.class, sender.receive(WAIT));
        return id;
    }

    private Message.TransferRequest transferRequest(UUID id, String fileName, byte[] content) throws Exception {
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(content);
        byte[] publicKey = crypto.newKeyPair().getPublic().getEncoded();
        return new Message.TransferRequest(id, "Sender", fileName, content.length, sha, publicKey);
    }

    private void sendChunks(MessageChannel sender, byte[] content) throws Exception {
        ChunkEncryptor encryptor = crypto.newEncryptor(null);
        for (int offset = 0, index = 0; offset < content.length; offset += CHUNK, index++) {
            int length = Math.min(CHUNK, content.length - offset);
            byte[] part = java.util.Arrays.copyOfRange(content, offset, offset + length);
            sender.send(toMessage(encryptor.encrypt(index, part, length)));
        }
    }

    private void receiveCompletely(String fileName, byte[] content) throws Exception {
        try (MessageChannel sender = connect()) {
            UUID id = request(sender, fileName, content);
            assertInstanceOf(Message.TransferAccept.class, sender.receive(WAIT));
            sendChunks(sender, content);
            sender.send(new Message.TransferComplete(id, chunkCount(content.length)));
            Message.TransferAck ack = assertInstanceOf(Message.TransferAck.class, sender.receive(WAIT));
            assertEquals(Message.TransferAck.OK, ack.status());
        }
        assertEquals(TransferStatus.COMPLETED, finalStatus());
    }

    private static Message.FileChunk toMessage(Chunk chunk) {
        return new Message.FileChunk(chunk.index(), chunk.iv(), chunk.ciphertext());
    }

    private static int chunkCount(int size) {
        return (size + CHUNK - 1) / CHUNK;
    }

    /** Waits for the next final status reported to the listener. */
    private TransferStatus finalStatus() throws InterruptedException {
        while (true) {
            TransferStatus status = statuses.poll(5, TimeUnit.SECONDS);
            assertNotNull(status, "No final status reported");
            if (status.isFinal()) {
                return status;
            }
        }
    }

    private void awaitStatus(TransferStatus expected) throws InterruptedException {
        while (true) {
            TransferStatus status = statuses.poll(5, TimeUnit.SECONDS);
            assertNotNull(status, "Status " + expected + " not reported");
            if (status == expected) {
                return;
            }
        }
    }

    private List<String> filesInDownloads() throws IOException {
        try (Stream<Path> files = Files.list(downloads)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static void assertErrorCode(ErrorCode expected, Message message) {
        Message.Error error = assertInstanceOf(Message.Error.class, message);
        assertEquals(expected, error.code());
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return data;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
