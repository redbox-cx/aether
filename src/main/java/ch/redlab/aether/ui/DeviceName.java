package ch.redlab.aether.ui;

/** Rules for the name of this device (Settings tab). Kept free of JavaFX so it can be unit tested. */
final class DeviceName {

    /** Longer names would be cut off in the device list of the other devices. */
    static final int MAX_LENGTH = 40;

    private DeviceName() {
    }

    /** @return an error text, or null if the name can be used */
    static String validate(String name) {
        String stripped = name == null ? "" : name.strip();
        if (stripped.isEmpty()) {
            return "Please enter a device name.";
        }
        if (stripped.length() > MAX_LENGTH) {
            return "The name can have at most " + MAX_LENGTH + " characters.";
        }
        return null;
    }
}
