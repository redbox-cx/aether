package ch.redlab.aether.protocol;

import java.time.Duration;

/** Fixed values of ATP v1. Change them here only, never hard-code them elsewhere. */
public final class ProtocolConstants {

    public static final int VERSION = 1;

    /** Header: version (1 byte) + type (1 byte) + payload length (4 bytes). */
    public static final int HEADER_SIZE = 6;

    public static final int DISCOVERY_PORT = 47800;
    public static final int TRANSFER_PORT = 47801;

    /** Maximum plaintext size of one FILE_CHUNK. */
    public static final int CHUNK_SIZE = 64 * 1024;

    /** AES-GCM IV: 4 random bytes per transfer + 8 bytes chunk index. */
    public static final int IV_SIZE = 12;

    /** AES-GCM authentication tag appended to every ciphertext (128 bit). */
    public static final int TAG_SIZE = 16;

    public static final int SHA256_SIZE = 32;

    /** Upper limit for a payload we accept, protects against huge length fields. */
    public static final int MAX_PAYLOAD = 1024 * 1024;

    public static final Duration HELLO_INTERVAL = Duration.ofSeconds(2);
    public static final Duration DEVICE_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration KEY_EXCHANGE_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DECISION_TIMEOUT = Duration.ofSeconds(300);

    private ProtocolConstants() {
    }
}
