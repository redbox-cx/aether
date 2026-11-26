package ch.redlab.aether.core;

import ch.redlab.aether.model.Device;
import ch.redlab.aether.model.Direction;
import ch.redlab.aether.model.FileInfo;
import ch.redlab.aether.model.Transfer;
import ch.redlab.aether.model.TransferStatus;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fake backend without any network, so the UI can be built and tested before
 * Realisierung I and II are done. Shows two fixed devices and simulates sending.
 * Replace it with the real implementation once it exists.
 */
public class DemoBackend implements AetherBackend {

    private final Settings settings;
    private final List<Device> devices;
    private final List<DeviceListener> deviceListeners = new CopyOnWriteArrayList<>();
    private final List<TransferListener> transferListeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, Transfer> transfers = new ConcurrentHashMap<>();

    public DemoBackend(Settings settings) {
        this.settings = settings;
        InetAddress local = InetAddress.getLoopbackAddress();
        this.devices = List.of(
                new Device("demo-1", "Desktop-PC", local, 47801, Instant.now()),
                new Device("demo-2", "Living room laptop", local, 47801, Instant.now()));
    }

    @Override
    public void start() {
        deviceListeners.forEach(l -> l.onDevicesChanged(devices));
    }

    @Override
    public void stop() {
        transfers.values().forEach(t -> t.setStatus(TransferStatus.CANCELLED));
    }

    @Override
    public Settings getSettings() {
        return settings;
    }

    @Override
    public List<Device> getDevices() {
        return devices;
    }

    @Override
    public void addDeviceListener(DeviceListener listener) {
        deviceListeners.add(listener);
    }

    @Override
    public void addTransferListener(TransferListener listener) {
        transferListeners.add(listener);
    }

    @Override
    public Transfer sendFile(Device target, Path file, FileInfo info) {
        Transfer transfer = new Transfer(UUID.randomUUID(), Direction.OUTGOING, target.name(), info);
        transfer.setSecurityCode("123456");
        transfers.put(transfer.getId(), transfer);
        Thread.ofVirtual().name("demo-transfer").start(() -> simulate(transfer));
        return transfer;
    }

    @Override
    public void cancel(UUID transferId) {
        Transfer transfer = transfers.get(transferId);
        if (transfer != null && !transfer.getStatus().isFinal()) {
            transfer.setStatus(TransferStatus.CANCELLED);
            fire(transfer);
        }
    }

    /** Waits 2 s for a fake "accept", then fills the progress bar over about 5 s. */
    private void simulate(Transfer transfer) {
        try {
            fire(transfer);
            Thread.sleep(2000);
            long size = transfer.getFile().size();
            for (int step = 1; step <= 50; step++) {
                if (transfer.getStatus() == TransferStatus.CANCELLED) {
                    return;
                }
                transfer.setStatus(TransferStatus.RUNNING);
                transfer.setBytesTransferred(size * step / 50);
                fire(transfer);
                Thread.sleep(100);
            }
            transfer.setStatus(TransferStatus.COMPLETED);
            fire(transfer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void fire(Transfer transfer) {
        transferListeners.forEach(l -> l.onTransferUpdated(transfer));
    }
}
