package ch.redlab.aether.protocol;

import ch.redlab.aether.model.Device;
import ch.redlab.aether.network.TcpConnection;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;

import static ch.redlab.aether.protocol.ProtocolConstants.HEADER_SIZE;

/**
 * Sends and receives ATP messages over a TCP connection. This is what {@code core} uses for a transfer.
 * <p>
 * {@link #send} is thread-safe (e.g. ERROR CANCELLED while chunks are sent),
 * {@link #receive} must only be called from one thread.
 */
public class MessageChannel implements Closeable {

    private final TcpConnection connection;

    public MessageChannel(TcpConnection connection) {
        this.connection = connection;
    }

    /** Connects to the transfer port of a discovered device. */
    public static MessageChannel connect(Device device) throws IOException {
        return new MessageChannel(TcpConnection.connect(device.address(), device.tcpPort(),
                ProtocolConstants.CONNECT_TIMEOUT));
    }

    public void send(Message message) throws IOException {
        connection.send(MessageCodec.encode(message));
    }

    /** Waits without time limit for the next message. */
    public Message receive() throws IOException {
        return receive(Duration.ZERO);
    }

    /**
     * Waits for the next message.
     * After a TIMEOUT or any other exception the channel must be closed, the stream position is undefined.
     *
     * @param timeout maximum wait, {@link Duration#ZERO} waits forever
     * @throws ProtocolException TIMEOUT if nothing arrived in time, otherwise the peer violated ATP
     * @throws EOFException      the other side closed the connection
     */
    public Message receive(Duration timeout) throws IOException {
        connection.setReadTimeout(timeout);
        try {
            byte[] header = new byte[HEADER_SIZE];
            connection.readFully(header, 0, HEADER_SIZE);
            int payloadLength = MessageCodec.payloadLength(header);
            byte[] frame = Arrays.copyOf(header, HEADER_SIZE + payloadLength);
            connection.readFully(frame, HEADER_SIZE, payloadLength);
            return MessageCodec.decode(frame);
        } catch (SocketTimeoutException e) {
            throw new ProtocolException(ErrorCode.TIMEOUT, "No message within " + timeout.toMillis() + " ms");
        }
    }

    public InetAddress getRemoteAddress() {
        return connection.getRemoteAddress();
    }

    /** Closes the connection; a thread blocked in {@link #receive} gets an exception. */
    @Override
    public void close() {
        connection.close();
    }
}
