package ch.redlab.aether.ui;

import ch.redlab.aether.model.Direction;
import ch.redlab.aether.model.FileInfo;
import ch.redlab.aether.model.Transfer;
import ch.redlab.aether.model.TransferStatus;
import ch.redlab.aether.protocol.Message;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Texts of the Progress tab, the status bar and the device name rules. */
class TransferTextTest {

    private static final long MIB = 1024 * 1024;

    @Test
    void titleShowsDirection() {
        assertEquals("To Desktop-PC", TransferText.title(transfer(Direction.OUTGOING, TransferStatus.REQUESTED)));
        assertEquals("From Desktop-PC", TransferText.title(transfer(Direction.INCOMING, TransferStatus.REQUESTED)));
    }

    @Test
    void runningShowsPercentAmountAndSpeed() {
        Transfer transfer = transfer(Direction.OUTGOING, TransferStatus.RUNNING);
        transfer.setBytesTransferred(MIB);

        assertEquals("25 % · 1.0 MiB of 4.0 MiB · 2.5 MiB/s",
                TransferText.detail(transfer, 2.5 * MIB));
    }

    @Test
    void finishedStates() {
        assertEquals("Sent · 4.0 MiB", TransferText.detail(transfer(Direction.OUTGOING, TransferStatus.COMPLETED), 0));
        assertEquals("Received · 4.0 MiB", TransferText.detail(transfer(Direction.INCOMING, TransferStatus.COMPLETED), 0));
        assertEquals("Desktop-PC declined", TransferText.detail(transfer(Direction.OUTGOING, TransferStatus.REJECTED), 0));
        Transfer timedOut = transfer(Direction.OUTGOING, TransferStatus.REJECTED);
        timedOut.setMessage(Message.TransferReject.TIMEOUT);
        assertEquals("No answer in time", TransferText.detail(timedOut, 0));
        assertEquals("Cancelled", TransferText.detail(transfer(Direction.INCOMING, TransferStatus.CANCELLED), 0));
    }

    @Test
    void waitingStates() {
        assertEquals("Waiting for Desktop-PC to accept",
                TransferText.detail(transfer(Direction.OUTGOING, TransferStatus.REQUESTED), 0));
        assertEquals("Waiting for your decision in the Requests tab",
                TransferText.detail(transfer(Direction.INCOMING, TransferStatus.REQUESTED), 0));
    }

    @Test
    void sizesAndSpeeds() {
        assertEquals("512 B", TransferText.size(512));
        assertEquals("1.5 KiB", TransferText.size(1536));
        assertEquals("3.0 MiB", TransferText.size(3 * MIB));
        assertEquals("0 B/s", TransferText.speed(0));
        assertEquals("0 B/s", TransferText.speed(-5));
        assertEquals("3.1 MiB/s", TransferText.speed(3.1 * MIB));
    }

    @Test
    void activeSummary() {
        assertEquals("No active transfers", TransferText.activeSummary(0));
        assertEquals("1 active transfer", TransferText.activeSummary(1));
        assertEquals("3 active transfers", TransferText.activeSummary(3));
    }

    @Test
    void deviceNameRules() {
        assertNull(DeviceName.validate("Henry's Laptop"));
        assertNotNull(DeviceName.validate("   "));
        assertNotNull(DeviceName.validate(null));
        assertNotNull(DeviceName.validate("x".repeat(DeviceName.MAX_LENGTH + 1)));
        assertNull(DeviceName.validate("  " + "x".repeat(DeviceName.MAX_LENGTH) + "  "));
    }

    private static Transfer transfer(Direction direction, TransferStatus status) {
        Transfer transfer = new Transfer(UUID.randomUUID(), direction, "Desktop-PC",
                new FileInfo("report.pdf", 4 * MIB, new byte[32]));
        transfer.setStatus(status);
        return transfer;
    }
}
