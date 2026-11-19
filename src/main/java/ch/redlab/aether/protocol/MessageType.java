package ch.redlab.aether.protocol;

/** Message types with their type byte as defined in Konzeptbericht 4.2. */
public enum MessageType {
    HELLO(0x01),
    TRANSFER_REQUEST(0x10),
    KEY_EXCHANGE(0x11),
    TRANSFER_ACCEPT(0x12),
    TRANSFER_REJECT(0x13),
    FILE_CHUNK(0x20),
    TRANSFER_COMPLETE(0x21),
    TRANSFER_ACK(0x22),
    ERROR(0x30);

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    /** The value written into the type byte of the header. */
    public int code() {
        return code;
    }

    /** Looks up a type by its header byte. */
    public static MessageType fromCode(int code) throws ProtocolException {
        for (MessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new ProtocolException(ErrorCode.IO_ERROR, "Unknown message type: 0x" + Integer.toHexString(code));
    }
}
