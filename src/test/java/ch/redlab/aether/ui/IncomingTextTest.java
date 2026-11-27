package ch.redlab.aether.ui;

import ch.redlab.aether.model.Direction;
import ch.redlab.aether.model.FileInfo;
import ch.redlab.aether.model.Transfer;
import ch.redlab.aether.model.TransferStatus;
import ch.redlab.aether.protocol.ErrorCode;
import ch.redlab.aether.protocol.Message;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Texts shown for an incoming transfer in the Requests tab (UC3). */
class IncomingTextTest {

    @Test
    void waitingForDecisionAsksToCompareTheCode() {
        assertTrue(IncomingText.status(transfer(TransferStatus.REQUESTED, null)).contains("security code"));
    }

    @Test
    void runningShowsPercentage() {
        Transfer transfer = transfer(TransferStatus.RUNNING, null);
        transfer.setBytesTransferred(250);

        assertEquals("Receiving … 25 %", IncomingText.status(transfer));
    }

    @Test
    void completed() {
        assertEquals("File received and verified.", IncomingText.status(transfer(TransferStatus.COMPLETED, null)));
    }

    @Test
    void declinedByUserAndByTimeoutAreDistinguished() {
        assertEquals("Declined.",
                IncomingText.status(transfer(TransferStatus.REJECTED, Message.TransferReject.USER)));
        assertEquals("No answer in time, declined automatically.",
                IncomingText.status(transfer(TransferStatus.REJECTED, Message.TransferReject.TIMEOUT)));
    }

    @Test
    void failuresAreExplained() {
        assertTrue(IncomingText.status(transfer(TransferStatus.FAILED, Message.TransferAck.HASH_MISMATCH))
                .contains("damaged"));
        assertTrue(IncomingText.status(transfer(TransferStatus.FAILED, ErrorCode.DECRYPT_FAILED.name()))
                .contains("decrypted"));
        assertEquals("Transfer failed (IO_ERROR).",
                IncomingText.status(transfer(TransferStatus.FAILED, ErrorCode.IO_ERROR.name())));
        assertEquals("Transfer failed.", IncomingText.status(transfer(TransferStatus.FAILED, null)));
    }

    @Test
    void cancelled() {
        assertEquals("Transfer cancelled.",
                IncomingText.status(transfer(TransferStatus.CANCELLED, ErrorCode.CANCELLED.name())));
    }

    @Test
    void tabTitleShowsPendingCount() {
        assertEquals("Requests", IncomingText.tabTitle(0));
        assertEquals("Requests (2)", IncomingText.tabTitle(2));
    }

    @Test
    void securityCodeIsSplitForReadingAloud() {
        assertEquals("052 395", IncomingText.spacedCode("052395"));
        assertEquals("12345", IncomingText.spacedCode("12345"));
    }

    private static Transfer transfer(TransferStatus status, String message) {
        Transfer transfer = new Transfer(UUID.randomUUID(), Direction.INCOMING, "Desktop-PC",
                new FileInfo("report.pdf", 1000, new byte[32]));
        transfer.setStatus(status);
        transfer.setMessage(message);
        return transfer;
    }
}
