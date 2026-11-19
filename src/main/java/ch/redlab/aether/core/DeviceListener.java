package ch.redlab.aether.core;

import ch.redlab.aether.model.Device;

import java.util.List;

/** Notified whenever a device appears or disappears. */
@FunctionalInterface
public interface DeviceListener {

    /** @param devices the complete, current device list (sorted by name) */
    void onDevicesChanged(List<Device> devices);
}
