package ch.redlab.aether.core;

import ch.redlab.aether.crypto.DefaultCryptoService;
import ch.redlab.aether.model.Device;
import ch.redlab.aether.model.FileInfo;
import ch.redlab.aether.model.Transfer;
import ch.redlab.aether.network.TcpServer;
import ch.redlab.aether.protocol.ProtocolConstants;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * The real backend: connects device discovery (UC1), the transfer server and the transfer flow
 * (UC2, UC3) with end-to-end encryption (UC4) behind {@link AetherBackend}.
 * <p>
 * Listener callbacks arrive on background threads; the UI wraps them in {@code Platform.runLater}.
 */
public class NetworkBackend implements AetherBackend {

    private final Settings settings;
    private final DiscoveryService discovery;
    private final TransferService transfers;
    private final int transferPort;
    private TcpServer server;

    public NetworkBackend(Settings settings) {
        this(settings, new DiscoveryService(settings),
                new TransferService(settings, new DefaultCryptoService()), ProtocolConstants.TRANSFER_PORT);
    }

    /** For tests: other ports and timeouts, so two backends can run on one machine. */
    NetworkBackend(Settings settings, DiscoveryService discovery, TransferService transfers, int transferPort) {
        this.settings = settings;
        this.discovery = discovery;
        this.transfers = transfers;
        this.transferPort = transferPort;
    }

    /**
     * Opens the transfer port first, so a device that finds us can connect right away, then starts discovery.
     * If either port is in use, nothing is left running and the exception reaches the caller.
     */
    @Override
    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("Backend already started");
        }
        TcpServer tcp = new TcpServer(transferPort, transfers::handleIncoming);
        tcp.start();
        try {
            discovery.start();
        } catch (IOException | RuntimeException e) {
            tcp.close();
            discovery.stop();
            throw e;
        }
        server = tcp;
    }

    /** Stops discovery and closes the transfer port and all incoming connections. Safe to call more than once. */
    @Override
    public synchronized void stop() {
        discovery.stop();
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Override
    public Settings getSettings() {
        return settings;
    }

    @Override
    public List<Device> getDevices() {
        return discovery.getRegistry().snapshot();
    }

    @Override
    public void addDeviceListener(DeviceListener listener) {
        discovery.getRegistry().addListener(listener);
    }

    @Override
    public void addTransferListener(TransferListener listener) {
        transfers.addListener(listener);
    }

    @Override
    public Transfer sendFile(Device target, Path file, FileInfo info) {
        return transfers.send(target, file, info);
    }

    @Override
    public void cancel(UUID transferId) {
        transfers.cancel(transferId);
    }

    /** The TCP port actually in use; differs from the configured one only in tests (port 0). */
    synchronized int getTransferPort() {
        if (server == null) {
            throw new IllegalStateException("Backend not started");
        }
        return server.getPort();
    }
}
