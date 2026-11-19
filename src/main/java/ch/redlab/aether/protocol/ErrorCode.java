package ch.redlab.aether.protocol;

/** Codes sent in an ERROR message. */
public enum ErrorCode {
    CANCELLED,
    TIMEOUT,
    DECRYPT_FAILED,
    UNSUPPORTED_VERSION,
    IO_ERROR
}
