package ch.redlab.aether.core;

import ch.redlab.aether.model.Device;
import ch.redlab.aether.protocol.Message;
import ch.redlab.aether.protocol.ProtocolConstants;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * List of the devices found on the network. Devices that have not sent a HELLO
 * for {@link ProtocolConstants#DEVICE_TIMEOUT} are removed.
 */
public class DeviceRegistry {

    private final Clock clock;
    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final List<DeviceListener> listeners = new CopyOnWriteArrayList<>();

    public DeviceRegistry() {
        this(Clock.systemUTC());
    }

    public DeviceRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records a HELLO.
     *
     * @return true if the device was not in the list yet
     */
    public boolean seen(Message.Hello hello, InetAddress from) {
        Instant now = clock.instant();
        Device updated = new Device(hello.deviceId(), hello.deviceName(), from, hello.tcpPort(), now);
        Device previous = devices.put(hello.deviceId(), updated);

        boolean changed = previous == null
                || !previous.name().equals(updated.name())
                || !previous.address().equals(updated.address())
                || previous.tcpPort() != updated.tcpPort();
        if (changed) {
            fireChanged();
        }
        return previous == null;
    }

    /** Removes devices that have been silent for too long. */
    public void removeExpired() {
        Instant limit = clock.instant().minus(ProtocolConstants.DEVICE_TIMEOUT);
        boolean removed = devices.values().removeIf(d -> !d.lastSeen().isAfter(limit));
        if (removed) {
            fireChanged();
        }
    }

    /** Current devices, sorted by name. */
    public List<Device> snapshot() {
        return devices.values().stream()
                .sorted(Comparator.comparing(Device::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public void addListener(DeviceListener listener) {
        listeners.add(listener);
    }

    private void fireChanged() {
        List<Device> current = snapshot();
        listeners.forEach(l -> l.onDevicesChanged(current));
    }
}
