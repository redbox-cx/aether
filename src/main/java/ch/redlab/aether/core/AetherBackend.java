package ch.redlab.aether.core;

import ch.redlab.aether.model.Device;
import ch.redlab.aether.model.FileInfo;
import ch.redlab.aether.model.Transfer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * The only entry point the UI uses.
 * <p>
 * Listener callbacks arrive on background threads. UI code must wrap them in
 * {@code Platform.runLater(...)} before touching JavaFX controls.
 */
public interface AetherBackend {

    /** Starts discovery and the transfer server. Throws if a port is already in use. */
    void start() throws IOException;

    /** Stops all threads and closes all sockets. */
    void stop();

    Settings getSettings();

    /** Current snapshot of the device list. */
    List<Device> getDevices();

    void addDeviceListener(DeviceListener listener);

    void addTransferListener(TransferListener listener);

    /**
     * Starts sending a file in the background and returns immediately.
     * Progress and result are reported through {@link TransferListener#onTransferUpdated}.
     *
     * @param info result of {@link FileInfo#fromFile(Path)}, computed when the user picked the file
     */
    Transfer sendFile(Device target, Path file, FileInfo info);

    /** Cancels a running transfer; the other side is notified with ERROR (CANCELLED). */
    void cancel(UUID transferId);
}
