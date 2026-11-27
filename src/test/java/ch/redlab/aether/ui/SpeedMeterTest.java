package ch.redlab.aether.ui;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Speed per transfer and in total, over a 2 s window (F-08, status bar). */
class SpeedMeterTest {

    private static final long SECOND = 1_000_000_000L;
    private final SpeedMeter meter = new SpeedMeter();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void unknownOrSingleSampleIsZero() {
        assertEquals(0, meter.bytesPerSecond(a, 0));
        meter.record(a, 1000, 0);
        assertEquals(0, meter.bytesPerSecond(a, 0));
    }

    @Test
    void steadyTransfer() {
        meter.record(a, 0, 0);
        meter.record(a, 1_000_000, SECOND);

        assertEquals(1_000_000, meter.bytesPerSecond(a, SECOND), 1);
    }

    @Test
    void stalledTransferDropsToZeroAfterTheWindow() {
        meter.record(a, 0, 0);
        meter.record(a, 1_000_000, SECOND);

        assertEquals(500_000, meter.bytesPerSecond(a, 2 * SECOND), 1);
        assertEquals(0, meter.bytesPerSecond(a, SECOND + SpeedMeter.WINDOW_NANOS));
    }

    @Test
    void onlyTheLastTwoSecondsCount() {
        meter.record(a, 0, 0);
        meter.record(a, 10_000_000, SECOND);
        meter.record(a, 11_000_000, 3 * SECOND);
        meter.record(a, 12_000_000, 4 * SECOND);

        // At 5 s the window starts at the sample from 3 s (the newest one at least 2 s old): 1 MB in 2 s
        assertEquals(500_000, meter.bytesPerSecond(a, 5 * SECOND), 1);
    }

    @Test
    void totalAddsUpAllTransfersAndForgetsRemovedOnes() {
        meter.record(a, 0, 0);
        meter.record(a, 1_000_000, SECOND);
        meter.record(b, 0, 0);
        meter.record(b, 3_000_000, SECOND);

        assertEquals(4_000_000, meter.totalBytesPerSecond(SECOND), 1);
        meter.remove(b);
        assertEquals(1_000_000, meter.totalBytesPerSecond(SECOND), 1);
    }
}
