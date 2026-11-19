package ch.redlab.aether.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

/** Two TCP connections on localhost that are connected to each other. */
public record LoopbackPair(TcpConnection client, TcpConnection server) implements AutoCloseable {

    public static LoopbackPair open() throws IOException {
        InetAddress localhost = InetAddress.getLoopbackAddress();
        try (ServerSocket listener = new ServerSocket(0, 1, localhost)) {
            TcpConnection client = TcpConnection.connect(localhost, listener.getLocalPort(), Duration.ofSeconds(2));
            return new LoopbackPair(client, new TcpConnection(listener.accept()));
        }
    }

    @Override
    public void close() {
        client.close();
        server.close();
    }
}
