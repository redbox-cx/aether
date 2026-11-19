package ch.redlab.aether.protocol;

import java.io.IOException;

/** Thrown when a peer violates ATP; the code can be sent back in an ERROR message. */
public class ProtocolException extends IOException {

    private final ErrorCode code;

    public ProtocolException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
