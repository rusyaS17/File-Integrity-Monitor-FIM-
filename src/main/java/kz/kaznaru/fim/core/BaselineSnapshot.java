package kz.kaznaru.fim.core;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable hashes captured for one normalized monitored root. */
public record BaselineSnapshot(Path monitoredRoot, Map<String, String> entries) {

    public BaselineSnapshot {
        monitoredRoot = Objects.requireNonNull(monitoredRoot, "monitoredRoot")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(entries, "entries");

        Map<String, String> validatedEntries = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String pathValue = Objects.requireNonNull(entry.getKey(), "entry path");
            String hash = Objects.requireNonNull(entry.getValue(), "entry hash");
            Path path;
            try {
                path = Path.of(pathValue);
            } catch (InvalidPathException exception) {
                throw new IllegalArgumentException("Invalid baseline entry path", exception);
            }
            if (!path.isAbsolute() || !path.normalize().toString().equals(pathValue)) {
                throw new IllegalArgumentException(
                        "Baseline entry paths must be normalized and absolute: " + pathValue);
            }
            if (!path.startsWith(monitoredRoot)) {
                throw new IllegalArgumentException(
                        "Baseline entry is outside the monitored root: " + pathValue);
            }
            if (!hash.matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "Baseline entry must contain a SHA-256 hash: " + pathValue);
            }
            String normalizedHash = hash.toLowerCase(Locale.ROOT);
            if (validatedEntries.putIfAbsent(pathValue, normalizedHash) != null) {
                throw new IllegalArgumentException(
                        "Duplicate normalized baseline path: " + pathValue);
            }
        }
        entries = Map.copyOf(validatedEntries);
    }

    public int entryCount() {
        return entries.size();
    }
}
