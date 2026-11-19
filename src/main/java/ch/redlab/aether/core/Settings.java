package ch.redlab.aether.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.prefs.Preferences;

/**
 * Settings of this device. The only data Aether stores permanently
 * (Java Preferences = registry on Windows, ~/.java on Linux).
 */
public class Settings {

    private static final String KEY_ID = "deviceId";
    private static final String KEY_NAME = "deviceName";
    private static final String KEY_DIR = "downloadDir";

    private final String deviceId;
    private volatile String deviceName;
    private volatile Path downloadDir;

    /** For tests: settings that are not loaded from or saved to the system. */
    public Settings(String deviceId, String deviceName, Path downloadDir) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.downloadDir = downloadDir;
    }

    /** Loads the stored settings; on first start a new device id is generated. */
    public static Settings load() {
        Preferences prefs = preferences();
        String id = prefs.get(KEY_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.put(KEY_ID, id);
        }
        String name = prefs.get(KEY_NAME, defaultDeviceName());
        Path dir = Path.of(prefs.get(KEY_DIR, defaultDownloadDir().toString()));
        return new Settings(id, name, dir);
    }

    public void save() {
        Preferences prefs = preferences();
        prefs.put(KEY_ID, deviceId);
        prefs.put(KEY_NAME, deviceName);
        prefs.put(KEY_DIR, downloadDir.toString());
    }

    /** Stable id, used to recognise our own HELLO messages and to identify the device. */
    public String getDeviceId() {
        return deviceId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    /** Where received files are saved (default: Documents/Aether). */
    public Path getDownloadDir() {
        return downloadDir;
    }

    public void setDownloadDir(Path downloadDir) {
        this.downloadDir = downloadDir;
    }

    private static Preferences preferences() {
        return Preferences.userNodeForPackage(Settings.class);
    }

    private static String defaultDeviceName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return System.getProperty("user.name", "Aether");
        }
    }

    private static Path defaultDownloadDir() {
        return Path.of(System.getProperty("user.home"), "Documents", "Aether");
    }
}
