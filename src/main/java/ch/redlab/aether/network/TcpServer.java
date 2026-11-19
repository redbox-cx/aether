package ch.redlab.aether.network;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Accepts incoming TCP connections (port 47801) and hands each one to the handler
 * on its own thread. The connection is closed when the handler returns.
 */
public class TcpServer implements Closeable {

    private static final System.Logger LOG = System.getLogger(TcpServer.class.getName());

    private final int port;
    private final Consumer<TcpConnection> handler;
    private final Set<TcpConnection> connections = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;

    /**
     * @param port    port to listen on, 0 picks a free port (for tests)
     * @param handler called once per connection, on a separate thread
     */
    public TcpServer(int port, Consumer<TcpConnection> handler) {
        this.port = port;
        this.handler = handler;
    }

    /** Opens the port and starts accepting. Throws a BindException if the port is already in use. */
    public synchronized void start() throws IOException {
        if (serverSocket != null) {
            throw new IllegalStateException("Server already started");
        }
        serverSocket = new ServerSocket(port);
        Thread.ofPlatform().name("tcp-accept").daemon().start(this::acceptLoop);
    }

    /** The port actually in use, useful when started with port 0. */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /** Stops accepting and closes all open connections. */
    @Override
    public void close() {
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
        connections.forEach(TcpConnection::close);
    }

    private void acceptLoop() {
        ServerSocket server = serverSocket;
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().name("tcp-connection").start(() -> handle(socket));
            } catch (IOException e) {
                if (!server.isClosed()) {
                    LOG.log(System.Logger.Level.WARNING, "Accepting a connection failed", e);
                }
            }
        }
    }

    private void handle(Socket socket) {
        TcpConnection connection;
        try {
            connection = new TcpConnection(socket);
        } catch (IOException e) {
            closeQuietly(socket);
            return;
        }
        connections.add(connection);
        try {
            if (!serverSocket.isClosed()) {
                handler.accept(connection);
            }
        } catch (RuntimeException e) {
            // A bug in one handler must not stop the server.
            LOG.log(System.Logger.Level.ERROR, "Connection handler failed", e);
        } finally {
            connection.close();
            connections.remove(connection);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing left to clean up.
        }
    }
}
