package ch.redlab.aether.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Metadata of the file being transferred.
 *
 * @param name   file name without directory
 * @param size   size in bytes
 * @param sha256 SHA-256 checksum (32 bytes), used to verify integrity after the transfer
 */
public record FileInfo(String name, long size, byte[] sha256) {

    /**
     * Reads the file once and computes its checksum.
     * Can take a while for large files, so do not call it on the JavaFX thread.
     */
    public static FileInfo fromFile(Path file) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                sha.update(buffer, 0, read);
            }
        }
        return new FileInfo(file.getFileName().toString(), Files.size(file), sha.digest());
    }
}
