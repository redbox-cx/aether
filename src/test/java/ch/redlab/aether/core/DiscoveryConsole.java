package ch.redlab.aether.core;

import ch.redlab.aether.model.Device;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Prints the device list to the console, to try discovery on real devices before the UI uses it.
 * Optional argument: device name.
 */
public class DiscoveryConsole {

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : Settings.load().getDeviceName();
        Settings settings = new Settings(UUID.randomUUID().toString(), name, Path.of("."));

        DiscoveryService discovery = new DiscoveryService(settings);
        discovery.getRegistry().addListener(devices -> {
            System.out.println(LocalTime.now().withNano(0) + "  " + devices.size() + " device(s)");
            if (devices.isEmpty()) {
                System.out.println("    No devices found");
            }
            for (Device d : devices) {
                System.out.println("    " + d.name() + " – " + d.address().getHostAddress());
            }
        });

        discovery.start();
        System.out.println("Discovery running as \"" + name + "\". Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(discovery::stop));
        Thread.currentThread().join();
    }
}
