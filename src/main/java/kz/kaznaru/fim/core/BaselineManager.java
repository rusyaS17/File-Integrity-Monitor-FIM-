package kz.kaznaru.fim.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Stores the trusted SHA-256 values (baseline) for monitored files. */
public class BaselineManager {

    public static final String VERSION_HEADER = "#FIM_BASELINE_V2";
    public static final String BACKUP_SUFFIX = ".bak";

    private final HashCalculator hashCalculator;
    private final Map<String, String> baseline;
    private Path baselineRoot;
    private boolean recoveredFromBackup;

    public BaselineManager() {
        this.hashCalculator = new HashCalculator();
        this.baseline = new LinkedHashMap<>();
        this.baselineRoot = null;
        this.recoveredFromBackup = false;
    }

    /** Calculates and stores the current hashes of all regular files in a folder. */
    public void createBaseline(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Monitored folder does not exist: " + directory);
        }

        baselineRoot = directory.toAbsolutePath().normalize();
        baseline.clear();
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String key = normalize(path);
                baseline.put(key, hashCalculator.calculateSHA256(path));
            }
        }
        recoveredFromBackup = false;
    }

    /** Saves paths and hashes atomically, retaining a backup of the previous baseline. */
    public void saveBaselineToFile(Path baselineFile) throws IOException {
        Path parent = baselineFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String data = serializeBaseline();
        Path absoluteBaseline = baselineFile.toAbsolutePath().normalize();
        try (TemporaryFile temporaryFile = TemporaryFile.create(parent,
                absoluteBaseline.getFileName().toString(), ".tmp")) {
            writeTemporaryFile(temporaryFile.path(), data);

            Path backupFile = getBackupFile(absoluteBaseline);
            if (Files.isRegularFile(absoluteBaseline)) {
                if (Files.exists(backupFile) && !Files.isRegularFile(backupFile)) {
                    throw new IOException("Baseline backup path is not a regular file: "
                            + backupFile);
                }
                Files.copy(absoluteBaseline, backupFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }

            replaceSafely(temporaryFile.path(), absoluteBaseline);
            recoveredFromBackup = false;
        }
    }

    /** Loads previously saved paths and hashes after the application is restarted. */
    public void loadBaselineFromFile(Path baselineFile) throws IOException {
        if (baselineRoot == null) {
            Path parent = baselineFile.toAbsolutePath().normalize().getParent();
            baselineRoot = parent == null ? Path.of("").toAbsolutePath().normalize() : parent;
        }
        loadBaselineFromFile(baselineFile, baselineRoot);
    }

    /** Loads a baseline using an explicit monitored root. */
    public void loadBaselineFromFile(Path baselineFile, Path monitoredRoot) throws IOException {
        Path absoluteBaseline = baselineFile.toAbsolutePath().normalize();
        baselineRoot = monitoredRoot.toAbsolutePath().normalize();
        recoveredFromBackup = false;

        IOException primaryFailure = null;
        if (Files.isRegularFile(absoluteBaseline)) {
            try {
                Map<String, String> loaded = parseBaseline(absoluteBaseline, baselineRoot);
                baseline.clear();
                baseline.putAll(loaded);
                return;
            } catch (IOException exception) {
                primaryFailure = exception;
            }
        } else {
            primaryFailure = new IOException("Baseline file not found: " + absoluteBaseline
                    + ". Run the program with the init command first.");
        }

        Path backupFile = getBackupFile(absoluteBaseline);
        if (Files.isRegularFile(backupFile)) {
            try {
                Map<String, String> recovered = parseBaseline(backupFile, baselineRoot);
                baseline.clear();
                baseline.putAll(recovered);
                restoreBackupSafely(backupFile, absoluteBaseline);
                recoveredFromBackup = true;
                return;
            } catch (IOException | RuntimeException backupFailure) {
                IOException recoveryFailure = new IOException(
                        "Primary baseline is invalid and the backup could not be recovered: "
                                + primaryFailure.getMessage(),
                        backupFailure);
                throw recoveryFailure;
            }
        }

        throw primaryFailure;
    }

    public boolean wasRecoveredFromBackup() {
        return recoveredFromBackup;
    }

    private Map<String, String> parseBaseline(Path baselineFile, Path root) throws IOException {
        List<String> lines = Files.readAllLines(baselineFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return new LinkedHashMap<>();
        }

        Map<String, String> loaded = new LinkedHashMap<>();
        Base64.Decoder decoder = Base64.getUrlDecoder();
        boolean versionTwo = lines.get(0).equals(VERSION_HEADER);

        for (int lineIndex = versionTwo ? 1 : 0; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex);
            if (line.isBlank()) {
                continue;
            }

            String[] parts = line.split("\\|", 2);
            if (parts.length != 2 || !parts[1].matches("(?i)[0-9a-f]{64}")) {
                throw new IOException("Invalid baseline entry at line " + (lineIndex + 1)
                        + " in " + baselineFile);
            }

            try {
                byte[] decoded = decoder.decode(parts[0]);
                String decodedPath = new String(decoded, StandardCharsets.UTF_8);
                String canonicalEncodedPath = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(decoded);
                if (!canonicalEncodedPath.equals(parts[0])) {
                    throw new IOException("Invalid canonical path encoding at baseline line "
                            + (lineIndex + 1));
                }
                String normalizedPath;

                if (versionTwo) {
                    if (decodedPath.isBlank()) {
                        throw new IOException("Empty baseline path at line " + (lineIndex + 1));
                    }
                    Path absolutePath = Path.of(decodedPath);
                    if (!absolutePath.isAbsolute()) {
                        throw new IOException("V2 baseline paths must be absolute at line "
                                + (lineIndex + 1));
                    }
                    normalizedPath = absolutePath.toAbsolutePath().normalize().toString();
                } else {
                    Path legacyPath = Path.of(decodedPath);
                    if (!legacyPath.isAbsolute()) {
                        throw new IOException("Legacy baseline paths must be absolute at line "
                                + (lineIndex + 1));
                    }
                    normalizedPath = legacyPath.toAbsolutePath().normalize().toString();
                }

                if (loaded.containsKey(normalizedPath)) {
                    throw new IOException("Duplicate baseline path at line " + (lineIndex + 1));
                }
                loaded.put(normalizedPath, parts[1].toLowerCase());
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid path encoding at baseline line " + (lineIndex + 1),
                        exception);
            }
        }
        return loaded;
    }

    public Map<String, String> getBaselineEntries() {
        return Map.copyOf(baseline);
    }

    private String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private String serializeBaseline() {
        StringBuilder data = new StringBuilder();
        data.append(VERSION_HEADER).append(System.lineSeparator());
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

        for (Map.Entry<String, String> entry : baseline.entrySet()) {
            if (baselineRoot == null) {
                throw new IllegalStateException(
                        "Baseline root is not set. Call createBaseline or loadBaselineFromFile first.");
            }
            String storedPath = Path.of(entry.getKey()).toAbsolutePath().normalize().toString();

            String encodedPath = encoder.encodeToString(
                    storedPath.getBytes(StandardCharsets.UTF_8));
            data.append(encodedPath)
                    .append('|')
                    .append(entry.getValue())
                    .append(System.lineSeparator());
        }
        return data.toString();
    }

    private void replaceSafely(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileAlreadyExistsException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void restoreBackupSafely(Path backupFile, Path target) throws IOException {
        Path parent = target.getParent();
        try (TemporaryFile temporaryFile = TemporaryFile.create(parent,
                target.getFileName().toString(), ".recovery.tmp")) {
            Files.copy(backupFile, temporaryFile.path(), StandardCopyOption.REPLACE_EXISTING);
            replaceSafely(temporaryFile.path(), target);
        }
    }

    private Path getBackupFile(Path baselineFile) {
        return baselineFile.resolveSibling(baselineFile.getFileName() + BACKUP_SUFFIX);
    }

    private void writeTemporaryFile(Path path, String data) throws IOException {
        ByteBuffer content = StandardCharsets.UTF_8.encode(data);
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            while (content.hasRemaining()) {
                channel.write(content);
            }
            channel.force(true);
        }
    }

    private static final class TemporaryFile implements AutoCloseable {

        private static final int CLEANUP_ATTEMPTS = 10;
        private static final long RETRY_DELAY_MILLIS = 50;

        private final Path path;

        private TemporaryFile(Path path) {
            this.path = path;
        }

        private static TemporaryFile create(Path parent, String prefix, String suffix)
                throws IOException {
            return new TemporaryFile(Files.createTempFile(parent, prefix, suffix));
        }

        private Path path() {
            return path;
        }

        @Override
        public void close() throws IOException {
            for (int attempt = 1; attempt <= CLEANUP_ATTEMPTS; attempt++) {
                try {
                    Files.deleteIfExists(path);
                    return;
                } catch (FileSystemException exception) {
                    if (attempt == CLEANUP_ATTEMPTS) {
                        throw exception;
                    }
                    try {
                        Thread.sleep(RETRY_DELAY_MILLIS);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        IOException cleanupInterrupted = new IOException(
                                "Interrupted while cleaning temporary file: " + path,
                                interruptedException);
                        cleanupInterrupted.addSuppressed(exception);
                        throw cleanupInterrupted;
                    }
                }
            }
        }
    }
}
