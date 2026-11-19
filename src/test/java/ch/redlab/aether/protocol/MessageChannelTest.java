package ch.redlab.aether.protocol;

import ch.redlab.aether.model.Device;
import ch.redlab.aether.network.LoopbackPair;
import ch.redlab.aether.network.TcpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(20)
class MessageChannelTest {

    private static final UUID ID = UUID.randomUUID();
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void allMessageTypesArriveInOrder() throws Exception {
        List<Message> messages = List.of(
                new Message.TransferRequest(ID, "Laptop", "bericht.pdf", 123_456, bytes(32, 1), bytes(44, 2)),
                new Message.KeyExchange(ID, bytes(44, 3)),
                new Message.TransferAccept(ID),
                new Message.FileChunk(0, bytes(12, 4), bytes(100, 5)),
                new Message.FileChunk(1, bytes(12, 6), bytes(16, 7)),
                new Message.TransferComplete(ID, 2),
                new Message.TransferAck(ID, Message.TransferAck.OK),
                new Message.TransferReject(ID, Message.TransferReject.TIMEOUT),
                new Message.Error(ErrorCode.CANCELLED, "Abgebrochen"));

        try (LoopbackPair pair = LoopbackPair.open()) {
            MessageChannel sender = new MessageChannel(pair.client());
            MessageChannel receiver = new MessageChannel(pair.server());
            for (Message message : messages) {
                sender.send(message);
            }
            for (Message expected : messages) {
                assertSameMessage(expected, receiver.receive());
            }
        }
    }

    @Test
    void fullSizeChunk() throws Exception {
        Message.FileChunk chunk = new Message.FileChunk(42, bytes(12, 1),
                bytes(ProtocolConstants.CHUNK_SIZE + ProtocolConstants.TAG_SIZE, 2));

        try (LoopbackPair pair = LoopbackPair.open()) {
            Thread.ofVirtual().start(() -> sendQuietly(new MessageChannel(pair.client()), chunk));

            assertSameMessage(chunk, new MessageChannel(pair.server()).receive());
        }
    }

    @Test
    void worksInBothDirections() throws Exception {
        try (LoopbackPair pair = LoopbackPair.open()) {
            MessageChannel sender = new MessageChannel(pair.client());
            MessageChannel receiver = new MessageChannel(pair.server());

            sender.send(new Message.TransferRequest(ID, "Laptop", "a.txt", 1, bytes(32, 0), bytes(44, 0)));
            assertInstanceOf(Message.TransferRequest.class, receiver.receive());
            receiver.send(new Message.KeyExchange(ID, bytes(44, 9)));

            assertInstanceOf(Message.KeyExchange.class, sender.receive());
        }
    }

    @Test
    void concurrentSendsDoNotInterleave() throws Exception {
        int perThread = 100;
        try (LoopbackPair pair = LoopbackPair.open()) {
            MessageChannel sender = new MessageChannel(pair.client());
            for (int thread = 1; thread <= 2; thread++) {
                byte marker = (byte) thread;
                Thread.ofVirtual().start(() -> {
                    for (int i = 0; i < perThread; i++) {
                        sendQuietly(sender, new Message.FileChunk(i, bytes(12, marker),
                                bytes(ProtocolConstants.CHUNK_SIZE, marker)));
                    }
                });
            }

            MessageChannel receiver = new MessageChannel(pair.server());
            long[] nextIndex = new long[3];
            for (int i = 0; i < 2 * perThread; i++) {
                var chunk = assertInstanceOf(Message.FileChunk.class, receiver.receive());
                byte marker = chunk.iv()[0];
                assertEquals(nextIndex[marker]++, chunk.chunkIndex());
                assertArrayEquals(bytes(ProtocolConstants.CHUNK_SIZE, marker), chunk.ciphertext());
            }
        }
    }

    @Test
    void connectsToDeviceAndTalksToServerHandler() throws Exception {
        TcpServer server = new TcpServer(0, connection -> {
            MessageChannel channel = new MessageChannel(connection);
            try {
                var request = (Message.TransferRequest) channel.receive(Duration.ofSeconds(5));
                channel.send(new Message.TransferAccept(request.transferId()));
            } catch (IOException ignored) {
                // The client then reads EOF and the test fails.
            }
        });
        server.start();
        Device device = new Device("id", "Desktop-PC", InetAddress.getLoopbackAddress(), server.getPort(), Instant.now());

        try (server; MessageChannel channel = MessageChannel.connect(device)) {
            channel.send(new Message.TransferRequest(ID, "Laptop", "a.txt", 1, bytes(32, 0), bytes(44, 0)));

            assertEquals(new Message.TransferAccept(ID), channel.receive(Duration.ofSeconds(5)));
        }
    }

    @Test
    void rejectsUnknownVersion() throws Exception {
        try (LoopbackPair pair = LoopbackPair.open()) {
            pair.client().send(HEX.parseHex("02" + "12" + "00000000"));

            assertCode(ErrorCode.UNSUPPORTED_VERSION, new MessageChannel(pair.server()));
        }
    }

    @Test
    void rejectsHugeLengthWithoutWaitingForData() throws Exception {
        try (LoopbackPair pair = LoopbackPair.open()) {
            pair.client().send(HEX.parseHex("01" + "20" + "7fffffff"));

            assertCode(ErrorCode.IO_ERROR, new MessageChannel(pair.server()));
        }
    }

    @Test
    void timeoutWhenNothingArrives() throws Exception {
        try (LoopbackPair pair = LoopbackPair.open()) {
            ProtocolException e = assertThrows(ProtocolException.class,
                    () -> new MessageChannel(pair.server()).receive(Duration.ofMillis(100)));

            assertEquals(ErrorCode.TIMEOUT, e.getCode());
        }
    }

    @Test
    void eofWhenPeerClosesBetweenMessages() throws Exception {
        try (LoopbackPair pair = LoopbackPair.open()) {
            new MessageChannel(pair.client()).send(new Message.TransferAccept(ID));
            pair.client().close();
            MessageChannel receiver = new MessageChannel(pair.server());

            assertEquals(new Message.TransferAccept(ID), receiver.receive());
            assertThrows(EOFException.class, receiver::receive);
        }
    }

    @Test
    void eofWhenPeerClosesInTheMiddleOfAMessage() throws Exception {
        try (LoopbackPair pair = LoopbackPair.open()) {
            byte[] frame = MessageCodec.encode(new Message.TransferAccept(ID));
            pair.client().send(Arrays.copyOf(frame, frame.length - 5));
            pair.client().close();

            assertThrows(EOFException.class, () -> new MessageChannel(pair.server()).receive());
        }
    }

    /** Records with byte arrays only compare references, so compare the encoded bytes instead. */
    private static void assertSameMessage(Message expected, Message actual) {
        assertArrayEquals(MessageCodec.encode(expected), MessageCodec.encode(actual));
    }

    private static void assertCode(ErrorCode expected, MessageChannel channel) {
        ProtocolException e = assertThrows(ProtocolException.class, channel::receive);
        assertEquals(expected, e.getCode());
    }

    private static void sendQuietly(MessageChannel channel, Message message) {
        try {
            channel.send(message);
        } catch (IOException ignored) {
            // The receiving side of the test fails in that case.
        }
    }

    private static byte[] bytes(int length, int value) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) value);
        return data;
    }
}
