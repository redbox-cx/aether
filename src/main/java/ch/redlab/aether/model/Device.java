package ch.redlab.aether.model;

import java.net.InetAddress;
import java.time.Instant;

/**
 * Another Aether instance found on the local network.
 *
 * @param id       stable UUID of the device (from its settings)
 * @param name     display name shown in the device list
 * @param address  IP address the HELLO came from
 * @param tcpPort  port the device accepts transfers on
 * @param lastSeen time of the last HELLO; devices silent for 10 s are removed
 */
public record Device(String id, String name, InetAddress address, int tcpPort, Instant lastSeen) {

    /** Returns a copy with an updated {@code lastSeen} timestamp. */
    public Device seenAt(Instant time) {
        return new Device(id, name, address, tcpPort, time);
    }
}
