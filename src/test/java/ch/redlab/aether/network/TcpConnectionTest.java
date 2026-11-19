package ch.redlab.aether.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class TcpConnectionTest {

    @Test
    void sendsAndReadsBytes() throws Exception {
        try (LoopbackPair pair = LoopbackPair.open()) {
            pair.client().send(new byte[]{1, 2, 3, 4, 5});

            byte[] received = new byte[5];
            pair.server().readFully(received, 0, 5);

            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, received);
        }
    }

    @Test
    void readFullyWaitsUntilAllBytesArrived() throws Exception {
        try (LoopbackPair pair = LoopbackPair.open()) {
            Thread.ofVirtual().start(() -> {
                try {
                    pair.client().send(new byte[]{1, 2});
                    Thread.sleep(100);
                    pair.client().send(new byte[]{3, 4});
                } catch (Exception ignored) {
                    // The assertion below fails in that case.
                }
            });

            byte[] received = new byte[4];
            pair.server().readFully(received, 0, 4);

            assertArrayEquals(new byte[]{1, 2, 3, 4}, received);
        }
    }

    @Test
    void readThrowsEofWhenPeerCloses() throws Exception {
        try (LoopbackPair pair = LoopbackPair.open()) {
            pair.client().close();

            assertThrows(EOFException.class, () -> pair.server().readFully(new byte[1], 0, 1));
        }
    }

    @Test
    void readTimesOut() throws Exception {
        try (LoopbackPair pair = LoopbackPair.open()) {
            pair.server().setReadTimeout(Duration.ofMillis(100));

            assertThrows(SocketTimeoutException.class, () -> pair.server().readFully(new byte[1], 0, 1));
        }
    }

    @Test
    void closeUnblocksWaitingRead() throws Exception {
        try (LoopbackPair pair = LoopbackPair.open()) {
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    // Close anyway.
                }
                pair.server().close();
            });

            assertThrows(IOException.class, () -> pair.server().readFully(new byte[1], 0, 1));
            assertTrue(pair.server().isClosed());
        }
    }

    @Test
    void connectFailsWhenNobodyListens() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }

        assertThrows(IOException.class,
                () -> TcpConnection.connect(InetAddress.getLoopbackAddress(), port, Duration.ofSeconds(1)));
    }
}
