package ch.redlab.aether.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class TcpServerTest {

    @Test
    void passesConnectionToHandler() throws Exception {
        try (TcpServer server = start(this::echoThreeBytes);
             TcpConnection client = connect(server)) {
            client.send(new byte[]{7, 8, 9});

            assertArrayEquals(new byte[]{7, 8, 9}, read(client, 3));
        }
    }

    @Test
    void handlesConnectionsInParallel() throws Exception {
        CountDownLatch bothConnected = new CountDownLatch(2);
        Consumer<TcpConnection> handler = connection -> {
            try {
                bothConnected.countDown();
                boolean parallel = bothConnected.await(5, TimeUnit.SECONDS);
                connection.send(new byte[]{(byte) (parallel ? 1 : 0)});
            } catch (Exception ignored) {
                // The client then reads EOF and the test fails.
            }
        };
        try (TcpServer server = start(handler);
             TcpConnection first = connect(server);
             TcpConnection second = connect(server)) {

            assertArrayEquals(new byte[]{1}, read(first, 1));
            assertArrayEquals(new byte[]{1}, read(second, 1));
        }
    }

    @Test
    void closesConnectionWhenHandlerReturns() throws Exception {
        try (TcpServer server = start(connection -> {
        });
             TcpConnection client = connect(server)) {

            assertThrows(EOFException.class, () -> read(client, 1));
        }
    }

    @Test
    void closeEndsOpenConnections() throws Exception {
        CountDownLatch handlerRunning = new CountDownLatch(1);
        TcpServer server = start(connection -> {
            handlerRunning.countDown();
            try {
                read(connection, 1);
            } catch (IOException ignored) {
                // Expected when the server is closed.
            }
        });
        try (TcpConnection client = connect(server)) {
            assertTrue(handlerRunning.await(5, TimeUnit.SECONDS));

            server.close();

            assertThrows(IOException.class, () -> read(client, 1));
        }
    }

    @Test
    void failingHandlerDoesNotStopServer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Consumer<TcpConnection> handler = connection -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("Simulated bug in handler");
            }
            echoThreeBytes(connection);
        };
        try (TcpServer server = start(handler)) {
            try (TcpConnection first = connect(server)) {
                assertThrows(EOFException.class, () -> read(first, 1));
            }
            try (TcpConnection second = connect(server)) {
                second.send(new byte[]{1, 2, 3});
                assertArrayEquals(new byte[]{1, 2, 3}, read(second, 3));
            }
        }
        assertEquals(2, calls.get());
    }

    @Test
    void startFailsWhenPortIsInUse() throws Exception {
        try (ServerSocket taken = new ServerSocket(0);
             TcpServer server = new TcpServer(taken.getLocalPort(), connection -> {
             })) {

            assertThrows(IOException.class, server::start);
        }
    }

    @Test
    void cannotStartTwice() throws Exception {
        try (TcpServer server = start(connection -> {
        })) {
            assertThrows(IllegalStateException.class, server::start);
        }
    }

    private static TcpServer start(Consumer<TcpConnection> handler) throws IOException {
        TcpServer server = new TcpServer(0, handler);
        server.start();
        return server;
    }

    private static TcpConnection connect(TcpServer server) throws IOException {
        return TcpConnection.connect(InetAddress.getLoopbackAddress(), server.getPort(), Duration.ofSeconds(2));
    }

    private static byte[] read(TcpConnection connection, int length) throws IOException {
        byte[] data = new byte[length];
        connection.readFully(data, 0, length);
        return data;
    }

    private void echoThreeBytes(TcpConnection connection) {
        try {
            connection.send(read(connection, 3));
        } catch (IOException ignored) {
            // The client then reads EOF and the test fails.
        }
    }
}
