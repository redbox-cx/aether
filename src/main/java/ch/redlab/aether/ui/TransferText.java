package ch.redlab.aether.ui;

import ch.redlab.aether.model.Direction;
import ch.redlab.aether.model.Transfer;
import ch.redlab.aether.protocol.Message;

import java.util.Locale;

/**
 * Texts of the Progress tab and the status bar (F-08). Kept free of JavaFX so it can be unit tested.
 */
final class TransferText {

    private static final String[] UNITS = {"KiB", "MiB", "GiB", "TiB"};

    private TransferText() {
    }

    /** "To Desktop-PC" or "From Desktop-PC". */
    static String title(Transfer transfer) {
        return (transfer.getDirection() == Direction.OUTGOING ? "To " : "From ") + transfer.getPeerName();
    }

    /** One line with the state, and for a running transfer the progress, amount and speed. */
    static String detail(Transfer transfer, double bytesPerSecond) {
        boolean outgoing = transfer.getDirection() == Direction.OUTGOING;
        long total = transfer.getFile().size();
        return switch (transfer.getStatus()) {
            case REQUESTED -> outgoing
                    ? "Waiting for " + transfer.getPeerName() + " to accept"
                    : "Waiting for your decision in the Requests tab";
            case ACCEPTED -> "Starting …";
            case RUNNING -> String.format(Locale.ROOT, "%d %% · %s of %s · %s",
                    Math.round(transfer.getProgress() * 100),
                    size(transfer.getBytesTransferred()), size(total), speed(bytesPerSecond));
            case COMPLETED -> (outgoing ? "Sent · " : "Received · ") + size(total);
            case REJECTED -> outgoing
                    ? (Message.TransferReject.TIMEOUT.equals(transfer.getMessage())
                            ? "No answer in time" : transfer.getPeerName() + " declined")
                    : IncomingText.status(transfer);
            case CANCELLED -> "Cancelled";
            case FAILED -> outgoing
                    ? "Failed" + (transfer.getMessage() == null ? "" : " (" + transfer.getMessage() + ")")
                    : IncomingText.status(transfer);
        };
    }

    /** Compact size: "512 B", "1.5 KiB", "3.0 MiB". */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }

    /** "0 B/s", "850 B/s", "3.1 MiB/s". */
    static String speed(double bytesPerSecond) {
        return size(Math.round(Math.max(0, bytesPerSecond))) + "/s";
    }

    /** Right part of the status bar. */
    static String activeSummary(int active) {
        return switch (active) {
            case 0 -> "No active transfers";
            case 1 -> "1 active transfer";
            default -> active + " active transfers";
        };
    }
}
