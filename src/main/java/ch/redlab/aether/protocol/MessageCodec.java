package ch.redlab.aether.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

import static ch.redlab.aether.protocol.ProtocolConstants.CHUNK_SIZE;
import static ch.redlab.aether.protocol.ProtocolConstants.HEADER_SIZE;
import static ch.redlab.aether.protocol.ProtocolConstants.IV_SIZE;
import static ch.redlab.aether.protocol.ProtocolConstants.MAX_PAYLOAD;
import static ch.redlab.aether.protocol.ProtocolConstants.SHA256_SIZE;
import static ch.redlab.aether.protocol.ProtocolConstants.TAG_SIZE;
import static ch.redlab.aether.protocol.ProtocolConstants.VERSION;

/**
 * Converts ATP messages to bytes and back (Konzeptbericht 4.2).
 * Frame: version (1 byte) | type (1 byte) | payload length (4 bytes) | payload.
 * <p>
 * Texts: 2-byte length + UTF-8 (writeUTF). UUIDs: as text. Byte fields: 4-byte length + bytes.
 */
public final class MessageCodec {

    /** X.509-encoded X25519 keys are 44 bytes; the limit only guards against garbage. */
    private static final int MAX_PUBLIC_KEY_SIZE = 256;

    private MessageCodec() {
    }

    /** Encodes a message into a complete frame (header + payload). */
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
        Header header = readHeader(frame);
        if (header.payloadLength() != frame.remaining()) {
            throw new ProtocolException(ErrorCode.IO_ERROR, "Payload length " + header.payloadLength()
                    + " does not match " + frame.remaining() + " received bytes");
        }
        byte[] payload = new byte[header.payloadLength()];
        frame.get(payload);
        return decodePayload(header.type(), payload);
    }

    /**
     * Validates the first {@link ProtocolConstants#HEADER_SIZE} bytes of a frame and returns
     * the announced payload length. Used by stream readers before they allocate the payload.
     */
    static int payloadLength(byte[] header) throws ProtocolException {
        return readHeader(ByteBuffer.wrap(header, 0, HEADER_SIZE)).payloadLength();
    }

    private record Header(MessageType type, int payloadLength) {
    }

    private static Header readHeader(ByteBuffer frame) throws ProtocolException {
        int version = Byte.toUnsignedInt(frame.get());
        if (version != VERSION) {
            throw new ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "Unsupported version: " + version);
        }
        MessageType type = MessageType.fromCode(Byte.toUnsignedInt(frame.get()));
        int payloadLength = frame.getInt();
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
            throw new ProtocolException(ErrorCode.IO_ERROR, "Invalid payload length: " + payloadLength);
        }
        return new Header(type, payloadLength);
    }

    private static byte[] encodePayload(Message message) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            switch (message) {
                case Message.Hello m -> {
                    out.writeUTF(m.deviceId());
                    out.writeUTF(m.deviceName());
                    out.writeInt(m.tcpPort());
                }
                case Message.TransferRequest m -> {
                    writeUuid(out, m.transferId());
                    out.writeUTF(m.senderName());
                    out.writeUTF(m.fileName());
                    out.writeLong(m.fileSize());
                    writeField(out, m.sha256());
                    writeField(out, m.senderPublicKey());
                }
                case Message.KeyExchange m -> {
                    writeUuid(out, m.transferId());
                    writeField(out, m.receiverPublicKey());
                }
                case Message.TransferAccept m -> writeUuid(out, m.transferId());
                case Message.TransferReject m -> {
                    writeUuid(out, m.transferId());
                    out.writeUTF(m.reason());
                }
                case Message.FileChunk m -> {
                    out.writeLong(m.chunkIndex());
                    writeField(out, m.iv());
                    writeField(out, m.ciphertext());
                }
                case Message.TransferComplete m -> {
                    writeUuid(out, m.transferId());
                    out.writeInt(m.chunkCount());
                }
                case Message.TransferAck m -> {
                    writeUuid(out, m.transferId());
                    out.writeUTF(m.status());
                }
                case Message.Error m -> {
                    out.writeUTF(m.code().name());
                    out.writeUTF(m.message());
                }
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
                case TRANSFER_REQUEST -> readTransferRequest(in);
                case KEY_EXCHANGE -> new Message.KeyExchange(readUuid(in), readPublicKey(in));
                case TRANSFER_ACCEPT -> new Message.TransferAccept(readUuid(in));
                case TRANSFER_REJECT -> new Message.TransferReject(readUuid(in),
                        readOneOf(in, Message.TransferReject.USER, Message.TransferReject.TIMEOUT));
                case FILE_CHUNK -> readFileChunk(in);
                case TRANSFER_COMPLETE -> new Message.TransferComplete(readUuid(in),
                        (int) checkNotNegative(in.readInt(), "chunkCount"));
                case TRANSFER_ACK -> new Message.TransferAck(readUuid(in),
                        readOneOf(in, Message.TransferAck.OK, Message.TransferAck.HASH_MISMATCH));
                case ERROR -> new Message.Error(readErrorCode(in), in.readUTF());
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

    private static Message.TransferRequest readTransferRequest(DataInputStream in) throws IOException {
        UUID transferId = readUuid(in);
        String senderName = in.readUTF();
        String fileName = in.readUTF();
        long fileSize = checkNotNegative(in.readLong(), "fileSize");
        byte[] sha256 = readField(in, SHA256_SIZE, SHA256_SIZE, "sha256");
        byte[] publicKey = readPublicKey(in);
        if (fileName.isBlank()) {
            throw new ProtocolException(ErrorCode.IO_ERROR, "TRANSFER_REQUEST without file name");
        }
        return new Message.TransferRequest(transferId, senderName, fileName, fileSize, sha256, publicKey);
    }

    private static Message.FileChunk readFileChunk(DataInputStream in) throws IOException {
        long chunkIndex = checkNotNegative(in.readLong(), "chunkIndex");
        byte[] iv = readField(in, IV_SIZE, IV_SIZE, "iv");
        byte[] ciphertext = readField(in, TAG_SIZE, CHUNK_SIZE + TAG_SIZE, "ciphertext");
        return new Message.FileChunk(chunkIndex, iv, ciphertext);
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeUTF(id.toString());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        String text = in.readUTF();
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(ErrorCode.IO_ERROR, "Invalid transferId: " + text);
        }
    }

    private static void writeField(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    /** Reads a length-prefixed byte field and checks its size before allocating memory. */
    private static byte[] readField(DataInputStream in, int min, int max, String name) throws IOException {
        int length = in.readInt();
        if (length < min || length > max || length > in.available()) {
            throw new ProtocolException(ErrorCode.IO_ERROR, "Invalid length of " + name + ": " + length);
        }
        return in.readNBytes(length);
    }

    private static byte[] readPublicKey(DataInputStream in) throws IOException {
        return readField(in, 1, MAX_PUBLIC_KEY_SIZE, "public key");
    }

    private static String readOneOf(DataInputStream in, String... allowed) throws IOException {
        String value = in.readUTF();
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return candidate;
            }
        }
        throw new ProtocolException(ErrorCode.IO_ERROR, "Unexpected value: " + value);
    }

    private static ErrorCode readErrorCode(DataInputStream in) throws IOException {
        String name = in.readUTF();
        try {
            return ErrorCode.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(ErrorCode.IO_ERROR, "Unknown error code: " + name);
        }
    }

    private static long checkNotNegative(long value, String name) throws ProtocolException {
        if (value < 0) {
            throw new ProtocolException(ErrorCode.IO_ERROR, "Negative " + name + ": " + value);
        }
        return value;
    }
}
