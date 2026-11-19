package ch.redlab.aether.protocol;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageCodecTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void helloRoundTrip() throws Exception {
        Message.Hello hello = new Message.Hello("3f2b8c1e-0d4a-4b6f-9a1e-7c5d2e8f1a3b", "Desktop-PC", 47801);

        assertEquals(hello, MessageCodec.decode(MessageCodec.encode(hello)));
    }

    @Test
    void helloWithUmlautsAndEmojiRoundTrip() throws Exception {
        Message.Hello hello = new Message.Hello("id", "Noels Läptop 💻", 47801);

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

    private static void assertCode(ErrorCode expected, byte[] frame) {
        ProtocolException e = assertThrows(ProtocolException.class, () -> MessageCodec.decode(frame));
        assertEquals(expected, e.getCode());
    }
}
