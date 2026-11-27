package ch.redlab.aether.ui;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Transfer speed per transfer over the last {@link #WINDOW_NANOS} (F-08, status bar).
 * Times are passed in as {@code System.nanoTime()} values so the meter can be tested without waiting.
 * Not thread-safe; used on the JavaFX thread only.
 */
final class SpeedMeter {

    /** Speed is averaged over the last 2 seconds; a stalled transfer drops to 0 after that. */
    static final long WINDOW_NANOS = 2_000_000_000L;

    private record Sample(long nanos, long bytes) {
    }

    private final Map<UUID, ArrayDeque<Sample>> samples = new HashMap<>();

    /** Records the total number of bytes a transfer has moved so far. */
    void record(UUID id, long bytesSoFar, long now) {
        ArrayDeque<Sample> queue = samples.computeIfAbsent(id, k -> new ArrayDeque<>());
        Sample last = queue.peekLast();
        if (last != null && bytesSoFar < last.bytes()) {
            queue.clear();
        }
        queue.addLast(new Sample(now, bytesSoFar));
        prune(queue, now);
    }

    /** Bytes per second of one transfer; 0 if unknown or stalled. */
    double bytesPerSecond(UUID id, long now) {
        ArrayDeque<Sample> queue = samples.get(id);
        if (queue == null) {
            return 0;
        }
        prune(queue, now);
        if (queue.size() < 2) {
            return 0;
        }
        Sample first = queue.peekFirst();
        Sample last = queue.peekLast();
        long elapsed = now - first.nanos();
        return elapsed <= 0 ? 0 : (last.bytes() - first.bytes()) * 1e9 / elapsed;
    }

    /** Sum of all transfers, shown in the status bar. */
    double totalBytesPerSecond(long now) {
        double total = 0;
        for (UUID id : samples.keySet()) {
            total += bytesPerSecond(id, now);
        }
        return total;
    }

    /** Forgets a finished transfer. */
    void remove(UUID id) {
        samples.remove(id);
    }

    /** Drops samples older than the window, but keeps the newest old one as the starting point. */
    private static void prune(ArrayDeque<Sample> queue, long now) {
        while (queue.size() >= 2) {
            Iterator<Sample> it = queue.iterator();
            it.next();
            Sample second = it.next();
            if (now - second.nanos() >= WINDOW_NANOS) {
                queue.removeFirst();
            } else {
                break;
            }
        }
    }
}
