package ch.redlab.aether.ui;

import ch.redlab.aether.model.Transfer;
import ch.redlab.aether.protocol.ErrorCode;
import ch.redlab.aether.protocol.Message;

import java.util.Locale;

/**
 * Texts of an incoming transfer in the Requests tab (UC3). Kept free of JavaFX so it can be unit tested.
 */
final class IncomingText {

    private IncomingText() {
    }

    /** Status line under a request card for the current state of the transfer. */
    static String status(Transfer transfer) {
        String message = transfer.getMessage();
        return switch (transfer.getStatus()) {
            case REQUESTED -> "Compare the security code with the code on the sender's screen.";
            case ACCEPTED -> "Accepted. Waiting for data …";
            case RUNNING -> String.format(Locale.ROOT, "Receiving … %d %%", Math.round(transfer.getProgress() * 100));
            case COMPLETED -> "File received and verified.";
            case REJECTED -> Message.TransferReject.TIMEOUT.equals(message)
                    ? "No answer in time, declined automatically."
                    : "Declined.";
            case CANCELLED -> "Transfer cancelled.";
            case FAILED -> failed(message);
        };
    }

    private static String failed(String message) {
        if (Message.TransferAck.HASH_MISMATCH.equals(message)) {
            return "The file was damaged in transit and has been discarded.";
        }
        if (ErrorCode.DECRYPT_FAILED.name().equals(message)) {
            return "The data could not be decrypted and has been discarded.";
        }
        if (ErrorCode.TIMEOUT.name().equals(message)) {
            return "The sender stopped responding.";
        }
        return message == null ? "Transfer failed." : "Transfer failed (" + message + ").";
    }

    /** "123456" -> "123 456", easier to compare aloud between two screens. */
    static String spacedCode(String code) {
        return code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code;
    }

    /** Text of the Requests tab: shows how many requests still wait for a decision. */
    static String tabTitle(int pending) {
        return pending == 0 ? "Requests" : "Requests (" + pending + ")";
    }
}
