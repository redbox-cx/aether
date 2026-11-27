package ch.redlab.aether.core;

import ch.redlab.aether.crypto.DefaultCryptoService;
import ch.redlab.aether.model.Device;
import ch.redlab.aether.model.FileInfo;
import ch.redlab.aether.model.Transfer;
import ch.redlab.aether.model.TransferStatus;
import ch.redlab.aether.network.UdpBroadcaster;
import ch.redlab.aether.protocol.Message;
import ch.redlab.aether.protocol.ProtocolConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs two real backends on localhost with the real crypto and sends a file from one to the other,
 * the way two Aether instances on the LAN would (UC2 + UC3 + UC4).
 */
@Timeout(30)
class NetworkBackendTest {

    private static final Duration SHORT = Duration.ofSeconds(5);

    @TempDir
    Path temp;

    private final List<NetworkBackend> started = new ArrayList<>();

    @AfterEach
    void stopAll() {
        started.forEach(NetworkBackend::stop);
    }

    @Test
    void sendsFileFromOneBackendToTheOther() throws Exception {
        Path downloads = Files.createDirectory(temp.resolve("downloads"));
        NetworkBackend receiver = start(backend("receiver", downloads));
        NetworkBackend sender = start(backend("sender", temp));

        BlockingQueue<String> receiverCode = new LinkedBlockingQueue<>();
        BlockingQueue<Transfer> receiverDone = new LinkedBlockingQueue<>();
        receiver.addTransferListener(new TransferListener() {
            @Override
            public void onIncomingRequest(IncomingRequest request) {
                receiverCode.add(request.getTransfer().getSecurityCode());
                request.accept();
            }

            @Override
            public void onTransferUpdated(Transfer transfer) {
                if (transfer.getStatus().isFinal()) {
                    receiverDone.add(transfer);
                }
            }
        });
        BlockingQueue<Transfer> senderDone = new LinkedBlockingQueue<>();
        sender.addTransferListener(finalStatusInto(senderDone));

        byte[] content = new byte[3 * ProtocolConstants.CHUNK_SIZE + 5];
        new Random(42).nextBytes(content);
        Path file = Files.write(temp.resolve("report.pdf"), content);
        Device target = new Device("receiver-id", "Receiver", InetAddress.getLoopbackAddress(),
                receiver.getTransferPort(), Instant.now());

        Transfer sent = sender.sendFile(target, file, FileInfo.fromFile(file));

        Transfer senderResult = senderDone.poll(10, TimeUnit.SECONDS);
        Transfer receiverResult = receiverDone.poll(10, TimeUnit.SECONDS);
        assertNotNull(senderResult, "sender reported no final status");
        assertNotNull(receiverResult, "receiver reported no final status");
        assertEquals(sent.getId(), senderResult.getId());
        assertEquals(TransferStatus.COMPLETED, senderResult.getStatus());
        assertEquals(TransferStatus.COMPLETED, receiverResult.getStatus());

        String code = receiverCode.poll(1, TimeUnit.SECONDS);
        assertNotNull(code);
        assertTrue(code.matches("\\d{6}"), "code: " + code);
        assertEquals(code, senderResult.getSecurityCode(), "both devices show the same security code");
        assertArrayEquals(content, Files.readAllBytes(downloads.resolve("report.pdf")));
    }

    @Test
    void deviceListComesFromDiscovery() throws Exception {
        DiscoveryService discovery = discovery(settings("self", temp));
        NetworkBackend backend = start(new NetworkBackend(settings("self", temp), discovery,
                transfers(settings("self", temp)), 0));
        BlockingQueue<List<Device>> changes = new LinkedBlockingQueue<>();
        backend.addDeviceListener(changes::add);

        discovery.handleHello(new Message.Hello("other-id", "Desktop-PC", ProtocolConstants.TRANSFER_PORT),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), freeUdpPort()));

        List<Device> list = changes.poll(5, TimeUnit.SECONDS);
        assertNotNull(list, "device listener was not called");
        assertEquals("Desktop-PC", list.get(0).name());
        assertEquals(List.of("Desktop-PC"), backend.getDevices().stream().map(Device::name).toList());
    }

    @Test
    void startFailsWhenTransferPortIsInUse() throws Exception {
        try (ServerSocket taken = new ServerSocket(0)) {
            NetworkBackend backend = new NetworkBackend(settings("x", temp), discovery(settings("x", temp)),
                    transfers(settings("x", temp)), taken.getLocalPort());

            assertThrows(IOException.class, backend::start);
            assertDoesNotThrow(backend::stop);
        }
    }

    @Test
    void stopWithoutStartIsSafe() {
        NetworkBackend backend = backend("x", temp);

        assertDoesNotThrow(backend::stop);
        assertDoesNotThrow(backend::stop);
    }

    // ------------------------------------------------------------------ helpers

    private NetworkBackend start(NetworkBackend backend) throws IOException {
        backend.start();
        started.add(backend);
        return backend;
    }

    /** A backend on free ports, so several can run next to each other and next to a real Aether. */
    private static NetworkBackend backend(String name, Path downloads) {
        Settings settings = settings(name, downloads);
        return new NetworkBackend(settings, discovery(settings), transfers(settings), 0);
    }

    private static Settings settings(String name, Path downloads) {
        return new Settings(name + "-id", name, downloads);
    }

    private static DiscoveryService discovery(Settings settings) {
        return new DiscoveryService(settings, new UdpBroadcaster(freeUdpPort()), new DeviceRegistry());
    }

    private static TransferService transfers(Settings settings) {
        return new TransferService(settings, new DefaultCryptoService(),
                new TransferService.Timeouts(SHORT, SHORT, SHORT));
    }

    private static int freeUdpPort() {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TransferListener finalStatusInto(BlockingQueue<Transfer> queue) {
        return new TransferListener() {
            @Override
            public void onIncomingRequest(IncomingRequest request) {
            }

            @Override
            public void onTransferUpdated(Transfer transfer) {
                if (transfer.getStatus().isFinal()) {
                    queue.add(transfer);
                }
            }
        };
    }
}
