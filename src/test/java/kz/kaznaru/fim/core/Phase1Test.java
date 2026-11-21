package kz.kaznaru.fim.core;

import kz.kaznaru.fim.application.ApplicationConfiguration;
import kz.kaznaru.fim.model.FileStatus;
import kz.kaznaru.fim.model.ScanReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class Phase1Test {

    @TempDir
    Path temporaryDirectory;

    @Test
    void applicationConfigurationUsesExplicitDirectory() {
        ApplicationConfiguration configuration =
                ApplicationConfiguration.forDirectory(temporaryDirectory);

        assertEquals(temporaryDirectory.toAbsolutePath().normalize(),
                configuration.applicationDirectory());
        assertEquals(temporaryDirectory.resolve("monitored").normalize(),
                configuration.monitoredDirectory());
        assertEquals(temporaryDirectory.resolve("baseline.txt").normalize(),
                configuration.baselineFile());
    }

    @Test
    void newBaselineUsesRelativeV2FormatAndLoadsIt() throws Exception {
        Path monitored = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Path file = monitored.resolve("sample.txt");
        Files.writeString(file, "original");
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");

        BaselineManager manager = new BaselineManager();
        manager.createBaseline(monitored);
        manager.saveBaselineToFile(baselineFile);

        String saved = Files.readString(baselineFile, StandardCharsets.UTF_8);
        assertTrue(saved.startsWith(BaselineManager.VERSION_HEADER + System.lineSeparator()));
        assertTrue(saved.contains(Base64.getUrlEncoder().withoutPadding().encodeToString(
                file.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8))
                .substring(0, 8)));

        BaselineManager loaded = new BaselineManager();
        loaded.loadBaselineFromFile(baselineFile, monitored);
        assertEquals(manager.getBaselineEntries(), loaded.getBaselineEntries());
    }

    @Test
    void legacyAbsoluteBaselineFormatRemainsSupported() throws Exception {
        Path monitored = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Path file = monitored.resolve("legacy.txt");
        Files.writeString(file, "legacy");
        String hash = new HashCalculator().calculateSHA256(file);
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");

        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String encodedPath = encoder.encodeToString(
                file.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
        Files.writeString(baselineFile,
                encodedPath + "|" + hash + System.lineSeparator(),
                StandardCharsets.UTF_8);

        BaselineManager manager = new BaselineManager();
        manager.loadBaselineFromFile(baselineFile, monitored);

        assertEquals(hash, manager.getBaselineEntries()
                .get(file.toAbsolutePath().normalize().toString()));
        assertFalse(manager.wasRecoveredFromBackup());
    }

    @Test
    void rejectsInvalidBaselineHash() throws Exception {
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");
        String encodedPath = Base64.getUrlEncoder().withoutPadding().encodeToString(
                temporaryDirectory.resolve("file.txt").toAbsolutePath().normalize()
                        .toString().getBytes(StandardCharsets.UTF_8));
        Files.writeString(baselineFile, encodedPath + "|not-a-sha256",
                StandardCharsets.UTF_8);

        BaselineManager manager = new BaselineManager();
        IOException exception = assertThrows(java.io.IOException.class,
                () -> manager.loadBaselineFromFile(baselineFile, temporaryDirectory));
        assertTrue(exception.getMessage().contains("Invalid baseline entry"));
    }

    @Test
    void rejectsNonCanonicalBase64Path() throws Exception {
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");
        Files.writeString(baselineFile,
                "***=|0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                StandardCharsets.UTF_8);

        BaselineManager manager = new BaselineManager();
        assertThrows(java.io.IOException.class,
                () -> manager.loadBaselineFromFile(baselineFile, temporaryDirectory));
    }

    @Test
    void rejectsDuplicateBaselinePaths() throws Exception {
        Path absolutePath = temporaryDirectory.resolve("sample.txt").toAbsolutePath().normalize();
        String encodedPath = Base64.getUrlEncoder().withoutPadding().encodeToString(
                absolutePath.toString().getBytes(StandardCharsets.UTF_8));
        String entry = encodedPath + "|"
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");
        Files.writeString(baselineFile, entry + System.lineSeparator() + entry,
                StandardCharsets.UTF_8);

        BaselineManager manager = new BaselineManager();
        IOException exception = assertThrows(java.io.IOException.class,
                () -> manager.loadBaselineFromFile(baselineFile, temporaryDirectory));
        assertTrue(exception.getMessage().contains("Duplicate baseline path"));
    }

    @Test
    void rejectsRelativeV2Path() throws Exception {
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");
        String encodedPath = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "outside.txt".getBytes(StandardCharsets.UTF_8));
        Files.writeString(baselineFile,
                BaselineManager.VERSION_HEADER + System.lineSeparator()
                        + encodedPath + "|"
                        + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                StandardCharsets.UTF_8);

        BaselineManager manager = new BaselineManager();
        IOException exception = assertThrows(java.io.IOException.class,
                () -> manager.loadBaselineFromFile(baselineFile, temporaryDirectory));
        assertTrue(exception.getMessage().contains("must be absolute"));
    }

    @Test
    void savesBackupAndRecoversCorruptPrimaryBaseline() throws Exception {
        Path monitored = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Files.writeString(monitored.resolve("sample.txt"), "original");
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");

        BaselineManager manager = new BaselineManager();
        manager.createBaseline(monitored);
        manager.saveBaselineToFile(baselineFile);
        manager.createBaseline(monitored);
        manager.saveBaselineToFile(baselineFile);

        Files.writeString(baselineFile, "corrupt baseline", StandardCharsets.UTF_8);

        BaselineManager recovered = new BaselineManager();
        recovered.loadBaselineFromFile(baselineFile, monitored);

        assertTrue(recovered.wasRecoveredFromBackup());
        assertEquals(1, recovered.getBaselineEntries().size());
        assertEquals(BaselineManager.VERSION_HEADER,
                Files.readString(baselineFile, StandardCharsets.UTF_8)
                        .split(System.lineSeparator(), 2)[0]);
        assertNoTemporaryFiles();

        Files.deleteIfExists(baselineFile.resolveSibling(
                baselineFile.getFileName() + BaselineManager.BACKUP_SUFFIX));
        Files.deleteIfExists(baselineFile);
        Files.deleteIfExists(monitored.resolve("sample.txt"));
        Files.deleteIfExists(monitored);
    }

    @Test
    void recoversBackupWhenPrimaryBaselineIsMissing() throws Exception {
        Path monitored = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Files.writeString(monitored.resolve("sample.txt"), "original");
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");

        BaselineManager manager = new BaselineManager();
        manager.createBaseline(monitored);
        manager.saveBaselineToFile(baselineFile);
        manager.createBaseline(monitored);
        manager.saveBaselineToFile(baselineFile);
        Files.delete(baselineFile);

        BaselineManager recovered = new BaselineManager();
        recovered.loadBaselineFromFile(baselineFile, monitored);

        assertTrue(recovered.wasRecoveredFromBackup());
        assertTrue(Files.isRegularFile(baselineFile));
        assertEquals(1, recovered.getBaselineEntries().size());
    }

    @Test
    void atomicSaveLeavesNoTemporaryFileAndCreatesBackup() throws Exception {
        Path monitored = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Files.writeString(monitored.resolve("sample.txt"), "before");
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");

        BaselineManager manager = new BaselineManager();
        manager.createBaseline(monitored);
        manager.saveBaselineToFile(baselineFile);

        Files.writeString(monitored.resolve("sample.txt"), "after");
        manager.createBaseline(monitored);
        manager.saveBaselineToFile(baselineFile);

        assertTrue(Files.isRegularFile(
                baselineFile.resolveSibling(baselineFile.getFileName()
                        + BaselineManager.BACKUP_SUFFIX)));
        try (java.util.stream.Stream<Path> files = Files.list(temporaryDirectory)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString()
                            .contains(".tmp"))
                    .count());
        }
    }

    @Test
    void failedSaveCleansTemporaryFile() throws Exception {
        Path monitored = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Files.writeString(monitored.resolve("sample.txt"), "before");
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");

        BaselineManager manager = new BaselineManager();
        manager.createBaseline(monitored);
        manager.saveBaselineToFile(baselineFile);

        Path backupPath = baselineFile.resolveSibling(
                baselineFile.getFileName() + BaselineManager.BACKUP_SUFFIX);
        Files.createDirectory(backupPath);

        Files.writeString(monitored.resolve("sample.txt"), "after");
        manager.createBaseline(monitored);
        assertThrows(IOException.class, () -> manager.saveBaselineToFile(baselineFile));
        assertNoTemporaryFiles();

        Files.delete(backupPath);
    }

    @Test
    void emptyBaselineIsValidForAnEmptyMonitoredDirectory() throws Exception {
        Path monitored = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");

        BaselineManager manager = new BaselineManager();
        manager.createBaseline(monitored);
        manager.saveBaselineToFile(baselineFile);

        BaselineManager loaded = new BaselineManager();
        loaded.loadBaselineFromFile(baselineFile, monitored);
        assertTrue(loaded.getBaselineEntries().isEmpty());
    }

    @Test
    void directoryScannerUsesIntegrityCheckerForExistingAndNewFiles() throws Exception {
        Path monitored = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Path safe = monitored.resolve("safe.txt");
        Path newFile = monitored.resolve("new.txt");
        Files.writeString(safe, "safe");
        Files.writeString(newFile, "new");

        TrackingIntegrityChecker checker = new TrackingIntegrityChecker();
        ScanReport report = new DirectoryScanner(checker).scan(monitored,
                java.util.Map.of(safe.toAbsolutePath().normalize().toString(),
                        new HashCalculator().calculateSHA256(safe)));

        assertEquals(1, report.count(FileStatus.SAFE));
        assertEquals(1, report.count(FileStatus.NEW));
        assertTrue(checker.isFileModifiedCalled);
        assertTrue(checker.getCurrentHashCalled);
    }

    private static final class TrackingIntegrityChecker extends IntegrityChecker {
        private boolean isFileModifiedCalled;
        private boolean getCurrentHashCalled;

        @Override
        public boolean isFileModified(Path filePath, String originalHash) {
            isFileModifiedCalled = true;
            try {
                return !getCurrentHash(filePath).equals(originalHash);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public String getCurrentHash(Path filePath) throws java.io.IOException {
            getCurrentHashCalled = true;
            return super.getCurrentHash(filePath);
        }
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(temporaryDirectory)) {
            assertEquals(0, files.filter(path -> {
                String fileName = path.getFileName().toString();
                return fileName.endsWith(".tmp") || fileName.endsWith(".recovery.tmp");
            }).count());
        }
    }
}
