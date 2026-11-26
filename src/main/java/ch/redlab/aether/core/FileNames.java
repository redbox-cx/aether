package ch.redlab.aether.core;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Turns file names received over the network into safe local names. */
final class FileNames {

    /** Names Windows does not allow for files, with or without extension. */
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static final int MAX_ATTEMPTS = 10_000;

    private FileNames() {
    }

    /**
     * Makes a name from the peer safe to use inside the download folder.
     * Directory parts are dropped (no path traversal), characters Windows does not allow
     * are replaced by '_' and trailing dots and spaces are removed.
     *
     * @return the safe name, or empty if nothing usable is left (e.g. "..")
     */
    static Optional<String> sanitize(String name) {
        String last = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);

        StringBuilder safe = new StringBuilder(last.length());
        for (char c : last.toCharArray()) {
            safe.append(c < 0x20 || "<>:\"|?*".indexOf(c) >= 0 ? '_' : c);
        }
        int end = safe.length();
        while (end > 0 && (safe.charAt(end - 1) == '.' || safe.charAt(end - 1) == ' ')) {
            end--;
        }
        String result = safe.substring(0, end).strip();
        if (result.isEmpty()) {
            return Optional.empty();
        }

        int dot = result.indexOf('.');
        String base = dot < 0 ? result : result.substring(0, dot);
        if (RESERVED.contains(base.toUpperCase(Locale.ROOT))) {
            result = "_" + result;
        }
        return Optional.of(result);
    }

    /**
     * Moves the file into the folder under the given name. If the name is taken,
     * " (1)", " (2)", ... is added before the extension, e.g. "report (1).pdf".
     *
     * @return the final location
     */
    static Path moveToFreeName(Path source, Path folder, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            Path target = folder.resolve(i == 0 ? name : base + " (" + i + ")" + extension);
            if (!folder.equals(target.getParent())) {
                // Defence in depth: sanitize() must never let a name leave the folder.
                throw new IOException("File name leaves the download folder: " + name);
            }
            try {
                return Files.move(source, target);
            } catch (FileAlreadyExistsException e) {
                // Taken, try the next number.
            }
        }
        throw new IOException("No free file name for " + name);
    }
}
