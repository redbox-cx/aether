package ch.redlab.aether.protocol;

import java.util.UUID;

/**
 * All ATP messages. Each record holds exactly the payload fields of Konzeptbericht 4.2,
 * in the order they are written on the wire.
 * <p>
 * Being a sealed interface, a {@code switch} over a Message is checked for completeness by the compiler.
 */
public sealed interface Message {

    MessageType type();

    /** UDP announcement, broadcast every 2 s and answered by unicast when a new device is seen. */
    record Hello(String deviceId, String deviceName, int tcpPort) implements Message {
        public MessageType type() {
            return MessageType.HELLO;
        }
    }

    /** Sender → receiver: asks to transfer a file and carries the sender's public key (X.509). */
    record TransferRequest(UUID transferId, String senderName, String fileName, long fileSize,
                           byte[] sha256, byte[] senderPublicKey) implements Message {
        public MessageType type() {
            return MessageType.TRANSFER_REQUEST;
        }
    }

    /** Receiver → sender: sent immediately after the request, so both sides can show the security code. */
    record KeyExchange(UUID transferId, byte[] receiverPublicKey) implements Message {
        public MessageType type() {
            return MessageType.KEY_EXCHANGE;
        }
    }

    /** Receiver → sender: the user accepted. */
    record TransferAccept(UUID transferId) implements Message {
        public MessageType type() {
            return MessageType.TRANSFER_ACCEPT;
        }
    }

    /** Receiver → sender: the user rejected or did not answer within 300 s. Connection is closed afterwards. */
    record TransferReject(UUID transferId, String reason) implements Message {
        public static final String USER = "USER";
        public static final String TIMEOUT = "TIMEOUT";

        public MessageType type() {
            return MessageType.TRANSFER_REJECT;
        }
    }

    /** Sender → receiver: one encrypted block, sent in ascending order starting at 0. */
    record FileChunk(long chunkIndex, byte[] iv, byte[] ciphertext) implements Message {
        public MessageType type() {
            return MessageType.FILE_CHUNK;
        }
    }

    /** Sender → receiver: all chunks have been sent. */
    record TransferComplete(UUID transferId, int chunkCount) implements Message {
        public MessageType type() {
            return MessageType.TRANSFER_COMPLETE;
        }
    }

    /** Receiver → sender: result of the checksum verification. */
    record TransferAck(UUID transferId, String status) implements Message {
        public static final String OK = "OK";
        public static final String HASH_MISMATCH = "HASH_MISMATCH";

        public MessageType type() {
            return MessageType.TRANSFER_ACK;
        }
    }

    /** Either side: aborts the transfer, the connection is closed afterwards. */
    record Error(ErrorCode code, String message) implements Message {
        public MessageType type() {
            return MessageType.ERROR;
        }
    }
}
