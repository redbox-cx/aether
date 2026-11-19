package ch.redlab.aether.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileInfoTest {

    @Test
    void computesNameSizeAndSha256(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("hello.txt"), "hello");

        FileInfo info = FileInfo.fromFile(file);

        assertEquals("hello.txt", info.name());
        assertEquals(5, info.size());
        // Known SHA-256 of "hello"
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                HexFormat.of().formatHex(info.sha256()));
    }
}
