package ch.redlab.aether.network;

import ch.redlab.aether.protocol.Message;
import ch.redlab.aether.protocol.MessageCodec;
import ch.redlab.aether.protocol.ProtocolConstants;
import ch.redlab.aether.protocol.ProtocolException;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * UDP channel for device discovery: sends HELLO as broadcast or unicast and
 * delivers received HELLOs together with the sender address.
 */
public class UdpBroadcaster implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(UdpBroadcaster.class.getName());
    private static final int RECEIVE_BUFFER = 8 * 1024;

    private final int port;
    private DatagramSocket socket;
    private Thread receiver;

    public UdpBroadcaster() {
        this(ProtocolConstants.DISCOVERY_PORT);
    }

    /** @param port a different port is only meant for tests */
    public UdpBroadcaster(int port) {
        this.port = port;
    }

    /** Binds the UDP port. Throws if it is already in use or blocked. */
    public void open() throws IOException {
        try {
            socket = new DatagramSocket(port);
            socket.setBroadcast(true);
        } catch (SocketException e) {
            throw new IOException("UDP-Port " + port + " ist belegt oder blockiert", e);
        }
    }

    /** Starts a background thread that passes every valid HELLO to {@code onHello}. */
    public void startReceiving(BiConsumer<Message.Hello, InetSocketAddress> onHello) {
        receiver = Thread.ofPlatform().daemon().name("udp-discovery").start(() -> receiveLoop(onHello));
    }

    /**
     * Sends to 255.255.255.255 and to the broadcast address of every active adapter,
     * because Windows sends the limited broadcast out of one adapter only.
     */
    public void broadcast(Message.Hello hello) {
        byte[] data = MessageCodec.encode(hello);
        for (InetAddress address : broadcastAddresses()) {
            try {
                socket.send(new DatagramPacket(data, data.length, address, port));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "Broadcast to " + address + " failed: " + e.getMessage());
            }
        }
    }

    /** Sends a HELLO directly to one device. */
    public void send(Message.Hello hello, InetSocketAddress target) throws IOException {
        byte[] data = MessageCodec.encode(hello);
        socket.send(new DatagramPacket(data, data.length, target));
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.close();
        }
        if (receiver != null) {
            receiver.interrupt();
        }
    }

    private void receiveLoop(BiConsumer<Message.Hello, InetSocketAddress> onHello) {
        byte[] buffer = new byte[RECEIVE_BUFFER];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                Message message = MessageCodec.decode(packet.getData(), packet.getOffset(), packet.getLength());
                if (message instanceof Message.Hello hello) {
                    onHello.accept(hello, (InetSocketAddress) packet.getSocketAddress());
                }
            } catch (ProtocolException e) {
                LOG.log(System.Logger.Level.DEBUG, "Ignored packet from " + packet.getAddress() + ": " + e.getMessage());
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    LOG.log(System.Logger.Level.WARNING, "Receiving failed: " + e.getMessage());
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Handling HELLO failed", e);
            }
        }
    }

    private static Set<InetAddress> broadcastAddresses() {
        Set<InetAddress> addresses = new LinkedHashSet<>();
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface nic : NetworkInterface.networkInterfaces().toList()) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    if (address.getBroadcast() != null) {
                        addresses.add(address.getBroadcast());
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Could not list network adapters: " + e.getMessage());
        }
        return addresses;
    }
}
