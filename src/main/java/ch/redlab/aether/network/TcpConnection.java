package ch.redlab.aether.network;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;

/**
 * One TCP connection to another device. Works with raw bytes only;
 * the message format is handled by {@code protocol.MessageChannel}.
 * <p>
 * {@link #send} may be called from several threads, reading must happen on a single thread.
 * {@link #close()} from another thread unblocks a waiting read.
 */
public class TcpConnection implements Closeable {

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;

    /** Wraps an already connected socket, e.g. one returned by {@code ServerSocket.accept()}. */
    public TcpConnection(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    /** Opens a connection; fails with an IOException if nobody answers within the timeout. */
    public static TcpConnection connect(InetAddress address, int port, Duration timeout) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, port), toMillis(timeout));
            return new TcpConnection(socket);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /** Writes all bytes and flushes them. Calls from different threads do not interleave. */
    public synchronized void send(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    /**
     * Reads exactly {@code length} bytes into the buffer.
     *
     * @throws EOFException                    if the other side closed the connection
     * @throws java.net.SocketTimeoutException if the read timeout expired
     */
    public void readFully(byte[] buffer, int offset, int length) throws IOException {
        in.readFully(buffer, offset, length);
    }

    /** Maximum time a read may block; {@link Duration#ZERO} waits forever. */
    public void setReadTimeout(Duration timeout) throws SocketException {
        socket.setSoTimeout(toMillis(timeout));
    }

    public InetAddress getRemoteAddress() {
        return socket.getInetAddress();
    }

    public boolean isClosed() {
        return socket.isClosed();
    }

    /** Closes the connection; safe to call more than once. */
    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing left to clean up.
        }
    }

    private static int toMillis(Duration timeout) {
        return (int) Math.min(timeout.toMillis(), Integer.MAX_VALUE);
    }
}
