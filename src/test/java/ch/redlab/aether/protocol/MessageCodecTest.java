package ch.redlab.aether.protocol;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageCodecTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final int HEADER = ProtocolConstants.HEADER_SIZE;
    private static final UUID ID = UUID.fromString("3f2b8c1e-0d4a-4b6f-9a1e-7c5d2e8f1a3b");

    @Test
    void helloRoundTrip() throws Exception {
        Message.Hello hello = new Message.Hello("3f2b8c1e-0d4a-4b6f-9a1e-7c5d2e8f1a3b", "Desktop-PC", 47801);

        assertEquals(hello, MessageCodec.decode(MessageCodec.encode(hello)));
    }

    @Test
    void helloWithNonAsciiNameRoundTrip() throws Exception {
        // Accented letter plus a character outside the BMP (musical G clef, written as surrogate pair).
        Message.Hello hello = new Message.Hello("id", "Zo\u00eb's laptop \uD834\uDD1E", 47801);

        assertEquals(hello, MessageCodec.decode(MessageCodec.encode(hello)));
    }

    @Test
    void helloMatchesWireFormat() {
        byte[] frame = MessageCodec.encode(new Message.Hello("id", "Laptop", 47801));

        String expected = "01" + "01" + "00000010"   // version, type HELLO, payload length 16
                + "0002" + "6964"                    // "id"
                + "0006" + "4c6170746f70"            // "Laptop"
                + "0000bab9";                        // 47801
        assertEquals(expected, HEX.formatHex(frame));
    }

    @Test
    void decodesFrameInsideLargerBuffer() throws Exception {
        Message.Hello hello = new Message.Hello("id", "Laptop", 47801);
        byte[] frame = MessageCodec.encode(hello);
        byte[] buffer = new byte[1024];
        Arrays.fill(buffer, (byte) 0x7f);
        System.arraycopy(frame, 0, buffer, 10, frame.length);

        assertEquals(hello, MessageCodec.decode(buffer, 10, frame.length));
    }

    @Test
    void rejectsUnknownVersion() {
        byte[] frame = MessageCodec.encode(new Message.Hello("id", "Laptop", 47801));
        frame[0] = 2;

        assertCode(ErrorCode.UNSUPPORTED_VERSION, frame);
    }

    @Test
    void rejectsUnknownType() {
        byte[] frame = MessageCodec.encode(new Message.Hello("id", "Laptop", 47801));
        frame[1] = (byte) 0x99;

        assertCode(ErrorCode.IO_ERROR, frame);
    }

    @Test
    void rejectsFrameShorterThanHeader() {
        assertCode(ErrorCode.IO_ERROR, HEX.parseHex("010100"));
    }

    @Test
    void rejectsTruncatedPayload() {
        byte[] frame = MessageCodec.encode(new Message.Hello("id", "Laptop", 47801));

        assertCode(ErrorCode.IO_ERROR, Arrays.copyOf(frame, frame.length - 1));
    }

    @Test
    void rejectsHugeLengthField() {
        assertCode(ErrorCode.IO_ERROR, HEX.parseHex("0101" + "7fffffff"));
    }

    @Test
    void rejectsLengthFieldThatLiesAboutPayload() {
        // Header claims 2 bytes, but deviceId says 100 bytes follow.
        assertCode(ErrorCode.IO_ERROR, HEX.parseHex("0101" + "00000002" + "0064"));
    }

    @Test
    void rejectsTrailingBytesInPayload() {
        assertCode(ErrorCode.IO_ERROR, HEX.parseHex("0101" + "00000011"
                + "0002" + "6964" + "0006" + "4c6170746f70" + "0000bab9" + "ff"));
    }

    @Test
    void rejectsInvalidPort() {
        assertCode(ErrorCode.IO_ERROR, MessageCodec.encode(new Message.Hello("id", "Laptop", 0)));
    }

    @Test
    void rejectsEmptyDeviceId() {
        assertCode(ErrorCode.IO_ERROR, MessageCodec.encode(new Message.Hello("", "Laptop", 47801)));
    }

    @Test
    void transferRequestRoundTrip() throws Exception {
        Message.TransferRequest request = new Message.TransferRequest(
                ID, "Laptop", "bericht.pdf", 50_000_000L, bytes(32, 1), bytes(44, 2));

        var decoded = assertInstanceOf(Message.TransferRequest.class, roundTrip(request));

        assertEquals(ID, decoded.transferId());
        assertEquals("Laptop", decoded.senderName());
        assertEquals("bericht.pdf", decoded.fileName());
        assertEquals(50_000_000L, decoded.fileSize());
        assertArrayEquals(request.sha256(), decoded.sha256());
        assertArrayEquals(request.senderPublicKey(), decoded.senderPublicKey());
    }

    @Test
    void keyExchangeRoundTrip() throws Exception {
        Message.KeyExchange exchange = new Message.KeyExchange(ID, bytes(44, 3));

        var decoded = assertInstanceOf(Message.KeyExchange.class, roundTrip(exchange));

        assertEquals(ID, decoded.transferId());
        assertArrayEquals(exchange.receiverPublicKey(), decoded.receiverPublicKey());
    }

    @Test
    void fileChunkRoundTrip() throws Exception {
        Message.FileChunk chunk = new Message.FileChunk(7, bytes(12, 4),
                bytes(ProtocolConstants.CHUNK_SIZE + ProtocolConstants.TAG_SIZE, 5));

        var decoded = assertInstanceOf(Message.FileChunk.class, roundTrip(chunk));

        assertEquals(7, decoded.chunkIndex());
        assertArrayEquals(chunk.iv(), decoded.iv());
        assertArrayEquals(chunk.ciphertext(), decoded.ciphertext());
    }

    @Test
    void messagesWithoutByteFieldsRoundTrip() throws Exception {
        Message[] messages = {
                new Message.TransferAccept(ID),
                new Message.TransferReject(ID, Message.TransferReject.USER),
                new Message.TransferReject(ID, Message.TransferReject.TIMEOUT),
                new Message.TransferComplete(ID, 763),
                new Message.TransferAck(ID, Message.TransferAck.OK),
                new Message.TransferAck(ID, Message.TransferAck.HASH_MISMATCH),
                new Message.Error(ErrorCode.CANCELLED, "Abgebrochen durch Benutzer"),
        };
        for (Message message : messages) {
            assertEquals(message, roundTrip(message));
        }
    }

    @Test
    void typeByteMatchesSpec() {
        assertEquals(0x12, MessageCodec.encode(new Message.TransferAccept(ID))[1]);
        assertEquals(0x20, MessageCodec.encode(new Message.FileChunk(0, bytes(12, 0), bytes(16, 0)))[1]);
        assertEquals(0x30, MessageCodec.encode(new Message.Error(ErrorCode.TIMEOUT, ""))[1]);
    }

    @Test
    void rejectsInvalidTransferId() {
        byte[] frame = MessageCodec.encode(new Message.TransferAccept(ID));
        frame[HEADER + 2] = 'x';

        assertCode(ErrorCode.IO_ERROR, frame);
    }

    @Test
    void rejectsUnknownRejectReason() {
        assertCode(ErrorCode.IO_ERROR, MessageCodec.encode(new Message.TransferReject(ID, "BORED")));
    }

    @Test
    void rejectsUnknownAckStatus() {
        assertCode(ErrorCode.IO_ERROR, MessageCodec.encode(new Message.TransferAck(ID, "MAYBE")));
    }

    @Test
    void rejectsWrongIvSize() {
        assertCode(ErrorCode.IO_ERROR, MessageCodec.encode(new Message.FileChunk(0, bytes(8, 0), bytes(16, 0))));
    }

    @Test
    void rejectsCiphertextShorterThanTag() {
        assertCode(ErrorCode.IO_ERROR, MessageCodec.encode(new Message.FileChunk(0, bytes(12, 0), bytes(15, 0))));
    }

    @Test
    void rejectsCiphertextLargerThanChunk() {
        byte[] tooBig = bytes(ProtocolConstants.CHUNK_SIZE + ProtocolConstants.TAG_SIZE + 1, 0);

        assertCode(ErrorCode.IO_ERROR, MessageCodec.encode(new Message.FileChunk(0, bytes(12, 0), tooBig)));
    }

    @Test
    void rejectsNegativeChunkIndex() {
        assertCode(ErrorCode.IO_ERROR, MessageCodec.encode(new Message.FileChunk(-1, bytes(12, 0), bytes(16, 0))));
    }

    @Test
    void rejectsWrongSha256Size() {
        assertCode(ErrorCode.IO_ERROR, MessageCodec.encode(
                new Message.TransferRequest(ID, "Laptop", "a.txt", 1, bytes(31, 0), bytes(44, 0))));
    }

    @Test
    void rejectsNegativeFileSize() {
        assertCode(ErrorCode.IO_ERROR, MessageCodec.encode(
                new Message.TransferRequest(ID, "Laptop", "a.txt", -1, bytes(32, 0), bytes(44, 0))));
    }

    @Test
    void rejectsEmptyPublicKey() {
        assertCode(ErrorCode.IO_ERROR, MessageCodec.encode(new Message.KeyExchange(ID, new byte[0])));
    }

    @Test
    void rejectsByteFieldLongerThanPayload() {
        byte[] frame = MessageCodec.encode(new Message.KeyExchange(ID, bytes(44, 0)));
        int keyLengthPos = HEADER + 2 + 36;
        frame[keyLengthPos] = 0x7f;

        assertCode(ErrorCode.IO_ERROR, frame);
    }

    @Test
    void rejectsUnknownErrorCode() {
        byte[] frame = MessageCodec.encode(new Message.Error(ErrorCode.TIMEOUT, ""));
        frame[HEADER + 2] = 'X';

        assertCode(ErrorCode.IO_ERROR, frame);
    }

    private static Message roundTrip(Message message) throws ProtocolException {
        return MessageCodec.decode(MessageCodec.encode(message));
    }

    private static byte[] bytes(int length, int value) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) value);
        return data;
    }

    private static void assertCode(ErrorCode expected, byte[] frame) {
        ProtocolException e = assertThrows(ProtocolException.class, () -> MessageCodec.decode(frame));
        assertEquals(expected, e.getCode());
    }
}
