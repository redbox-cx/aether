package ch.redlab.aether.core;

import ch.redlab.aether.network.UdpBroadcaster;
import ch.redlab.aether.protocol.Message;
import ch.redlab.aether.protocol.ProtocolConstants;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Device discovery (UC1): announces this device every 2 s, answers new devices
 * directly and keeps the {@link DeviceRegistry} up to date.
 */
public class DiscoveryService {

    private static final System.Logger LOG = System.getLogger(DiscoveryService.class.getName());

    private final Settings settings;
    private final UdpBroadcaster broadcaster;
    private final DeviceRegistry registry;
    private ScheduledExecutorService scheduler;

    public DiscoveryService(Settings settings) {
        this(settings, new UdpBroadcaster(), new DeviceRegistry());
    }

    public DiscoveryService(Settings settings, UdpBroadcaster broadcaster, DeviceRegistry registry) {
        this.settings = settings;
        this.broadcaster = broadcaster;
        this.registry = registry;
    }

    /** Opens the UDP port and starts announcing. Throws if the port is in use. */
    public void start() throws IOException {
        broadcaster.open();
        broadcaster.startReceiving(this::handleHello);

        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("discovery").factory());
        long interval = ProtocolConstants.HELLO_INTERVAL.toMillis();
        scheduler.scheduleAtFixedRate(() -> broadcaster.broadcast(ownHello()), 0, interval, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(registry::removeExpired, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        broadcaster.close();
    }

    public DeviceRegistry getRegistry() {
        return registry;
    }

    void handleHello(Message.Hello hello, InetSocketAddress from) {
        if (hello.deviceId().equals(settings.getDeviceId())) {
            return;
        }
        boolean isNew = registry.seen(hello, from.getAddress());
        if (isNew) {
            // Answer right away so the new device does not have to wait for our next broadcast (N-04).
            try {
                broadcaster.send(ownHello(), from);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Reply to " + from + " failed: " + e.getMessage());
            }
        }
    }

    private Message.Hello ownHello() {
        return new Message.Hello(settings.getDeviceId(), settings.getDeviceName(), ProtocolConstants.TRANSFER_PORT);
    }
}
