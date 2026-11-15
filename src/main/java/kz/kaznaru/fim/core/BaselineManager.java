package kz.kaznaru.fim.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Stores the trusted SHA-256 values (baseline) for monitored files. */
public class BaselineManager {

    private final HashCalculator hashCalculator;
    private final Map<String, String> baseline;

    public BaselineManager() {
        this.hashCalculator = new HashCalculator();
        this.baseline = new LinkedHashMap<>();
    }

    /** Calculates and stores the current hashes of all regular files in a folder. */
    public void createBaseline(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Monitored folder does not exist: " + directory);
        }

        baseline.clear();
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String key = normalize(path);
                baseline.put(key, hashCalculator.calculateSHA256(path));
            }
        }
    }

    /** Saves paths and hashes. Paths are Base64-encoded to keep the file format safe. */
    public void saveBaselineToFile(Path baselineFile) throws IOException {
        StringBuilder data = new StringBuilder();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

        for (Map.Entry<String, String> entry : baseline.entrySet()) {
            String encodedPath = encoder.encodeToString(
                    entry.getKey().getBytes(StandardCharsets.UTF_8));
            data.append(encodedPath)
                    .append('|')
                    .append(entry.getValue())
                    .append(System.lineSeparator());
        }

        Path parent = baselineFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(baselineFile, data.toString(), StandardCharsets.UTF_8);
    }

    /** Loads previously saved paths and hashes after the application is restarted. */
    public void loadBaselineFromFile(Path baselineFile) throws IOException {
        if (!Files.isRegularFile(baselineFile)) {
            throw new IOException("Baseline file not found: " + baselineFile
                    + ". Run the program with the init command first.");
        }

        Map<String, String> loaded = new LinkedHashMap<>();
        Base64.Decoder decoder = Base64.getUrlDecoder();
        int lineNumber = 0;

        for (String line : Files.readAllLines(baselineFile, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }

            String[] parts = line.split("\\|", 2);
            if (parts.length != 2 || !parts[1].matches("(?i)[0-9a-f]{64}")) {
                throw new IOException("Invalid baseline entry at line " + lineNumber);
            }

            try {
                String path = new String(decoder.decode(parts[0]), StandardCharsets.UTF_8);
                loaded.put(path, parts[1].toLowerCase());
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid path encoding at baseline line " + lineNumber,
                        exception);
            }
        }

        baseline.clear();
        baseline.putAll(loaded);
    }

    public Map<String, String> getBaselineEntries() {
        return Map.copyOf(baseline);
    }

    private String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
