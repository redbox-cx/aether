package ch.redlab.aether.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static ch.redlab.aether.protocol.ProtocolConstants.HEADER_SIZE;
import static ch.redlab.aether.protocol.ProtocolConstants.MAX_PAYLOAD;
import static ch.redlab.aether.protocol.ProtocolConstants.VERSION;

/**
 * Converts ATP messages to bytes and back (Konzeptbericht 4.2).
 * Frame: version (1 byte) | type (1 byte) | payload length (4 bytes) | payload.
 * <p>
 * Currently implemented: HELLO. The other types follow in Realisierung II.
 */
public final class MessageCodec {

    private MessageCodec() {
    }

    /** Encodes a message into a complete frame (header + payload), e.g. for a UDP datagram. */
    public static byte[] encode(Message message) {
        byte[] payload = encodePayload(message);
        return ByteBuffer.allocate(HEADER_SIZE + payload.length)
                .put((byte) VERSION)
                .put((byte) message.type().code())
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    /** Decodes a byte array that contains exactly one frame. */
    public static Message decode(byte[] data) throws ProtocolException {
        return decode(data, 0, data.length);
    }

    /**
     * Decodes exactly one frame from a part of a byte array.
     * For UDP: {@code decode(packet.getData(), packet.getOffset(), packet.getLength())}.
     */
    public static Message decode(byte[] data, int offset, int length) throws ProtocolException {
        if (length < HEADER_SIZE) {
            throw new ProtocolException(ErrorCode.IO_ERROR, "Frame too short: " + length + " bytes");
        }
        ByteBuffer frame = ByteBuffer.wrap(data, offset, length);
        int version = Byte.toUnsignedInt(frame.get());
        if (version != VERSION) {
            throw new ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "Unsupported version: " + version);
        }
        MessageType type = MessageType.fromCode(Byte.toUnsignedInt(frame.get()));
        int payloadLength = frame.getInt();
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
            throw new ProtocolException(ErrorCode.IO_ERROR, "Invalid payload length: " + payloadLength);
        }
        if (payloadLength != frame.remaining()) {
            throw new ProtocolException(ErrorCode.IO_ERROR,
                    "Payload length " + payloadLength + " does not match " + frame.remaining() + " received bytes");
        }
        byte[] payload = new byte[payloadLength];
        frame.get(payload);
        return decodePayload(type, payload);
    }

    private static byte[] encodePayload(Message message) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            switch (message) {
                case Message.Hello hello -> {
                    out.writeUTF(hello.deviceId());
                    out.writeUTF(hello.deviceName());
                    out.writeInt(hello.tcpPort());
                }
                default -> throw new UnsupportedOperationException("Not implemented yet: " + message.type());
            }
        } catch (IOException e) {
            // Only possible for texts longer than 65535 bytes, ByteArrayOutputStream itself never fails.
            throw new IllegalArgumentException("Cannot encode " + message.type() + ": " + e.getMessage(), e);
        }
        return bytes.toByteArray();
    }

    private static Message decodePayload(MessageType type, byte[] payload) throws ProtocolException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        Message message;
        try {
            message = switch (type) {
                case HELLO -> readHello(in);
                default -> throw new ProtocolException(ErrorCode.IO_ERROR, "Not implemented yet: " + type);
            };
            if (in.available() > 0) {
                throw new ProtocolException(ErrorCode.IO_ERROR, "Unexpected bytes after " + type + " payload");
            }
        } catch (ProtocolException e) {
            throw e;
        } catch (IOException e) {
            throw new ProtocolException(ErrorCode.IO_ERROR, "Malformed " + type + " payload: " + e);
        }
        return message;
    }

    private static Message.Hello readHello(DataInputStream in) throws IOException {
        String deviceId = in.readUTF();
        String deviceName = in.readUTF();
        int tcpPort = in.readInt();
        if (deviceId.isBlank()) {
            throw new ProtocolException(ErrorCode.IO_ERROR, "HELLO without device id");
        }
        if (tcpPort < 1 || tcpPort > 65535) {
            throw new ProtocolException(ErrorCode.IO_ERROR, "HELLO with invalid TCP port: " + tcpPort);
        }
        return new Message.Hello(deviceId, deviceName, tcpPort);
    }
}
