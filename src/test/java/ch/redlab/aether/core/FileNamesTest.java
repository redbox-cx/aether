package ch.redlab.aether.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileNamesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "report.pdf          | report.pdf",
            "../../evil.txt      | evil.txt",
            "..\\..\\evil.txt    | evil.txt",
            "C:\\Windows\\a.dll  | a.dll",
            "/etc/passwd         | passwd",
            "a<b>c:d.txt         | a_b_c_d.txt",
            "name.               | name",
            "CON.txt             | _CON.txt",
            "nul                 | _nul",
            "Zo\u00eb's file.txt | Zo\u00eb's file.txt"
    })
    void sanitizesNames(String input, String expected) {
        assertEquals(Optional.of(expected), FileNames.sanitize(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "../", "dir/", "...", "   "})
    void rejectsNamesWithoutContent(String input) {
        assertTrue(FileNames.sanitize(input).isEmpty());
    }

    @Test
    void addsNumberWhenNameIsTaken(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("report.pdf"), "old");

        Path first = FileNames.moveToFreeName(Files.writeString(dir.resolve("tmp1"), "a"), dir, "report.pdf");
        Path second = FileNames.moveToFreeName(Files.writeString(dir.resolve("tmp2"), "b"), dir, "report.pdf");
        Path noExtension = FileNames.moveToFreeName(Files.writeString(dir.resolve("tmp3"), "c"), dir, "README");
        Path noExtensionAgain = FileNames.moveToFreeName(Files.writeString(dir.resolve("tmp4"), "d"), dir, "README");

        assertEquals(dir.resolve("report (1).pdf"), first);
        assertEquals(dir.resolve("report (2).pdf"), second);
        assertEquals(dir.resolve("README"), noExtension);
        assertEquals(dir.resolve("README (1)"), noExtensionAgain);
        assertEquals("old", Files.readString(dir.resolve("report.pdf")));
    }
}
