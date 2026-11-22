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
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

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
        assertEquals(temporaryDirectory.resolve("fim.db").normalize(),
                configuration.databaseFile());
    }

    @Test
    void applicationConfigurationAcceptsMonitoringSettings() {
        ApplicationConfiguration configuration =
                ApplicationConfiguration.forDirectory(
                        temporaryDirectory, Duration.ofMillis(75), Duration.ofSeconds(12));

        assertEquals(Duration.ofMillis(75), configuration.watchDebounceDuration());
        assertEquals(Duration.ofSeconds(12), configuration.reconciliationInterval());
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationConfiguration.forDirectory(
                        temporaryDirectory, Duration.ofMillis(-1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationConfiguration.forDirectory(
                        temporaryDirectory, Duration.ZERO, Duration.ZERO));
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
        assertTrue(checker.hashComparisonCalled);
        assertTrue(checker.getCurrentHashCalled);
    }

    @Test
    void preparesAnEmptyBaselineSnapshotForAnEmptyDirectory() throws Exception {
        Path monitored = Files.createDirectory(temporaryDirectory.resolve("monitored"));

        BaselineSnapshot snapshot = new BaselineManager().prepareBaseline(monitored);

        assertEquals(monitored.toAbsolutePath().normalize(), snapshot.monitoredRoot());
        assertTrue(snapshot.entries().isEmpty());
        assertEquals(0, snapshot.entryCount());
        assertFalse(Files.exists(temporaryDirectory.resolve("baseline.txt")));
        assertFalse(Files.exists(temporaryDirectory.resolve("baseline.txt.bak")));
    }

    @Test
    void preparesCorrectHashesForFilesRecursively() throws Exception {
        Path monitored = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Path topLevelFile = monitored.resolve("top.txt");
        Path nestedDirectory = Files.createDirectory(monitored.resolve("nested"));
        Path nestedFile = nestedDirectory.resolve("nested.txt");
        Files.writeString(topLevelFile, "top-level");
        Files.writeString(nestedFile, "nested");

        BaselineSnapshot snapshot = new BaselineManager().prepareBaseline(monitored);

        HashCalculator hashCalculator = new HashCalculator();
        assertEquals(2, snapshot.entryCount());
        assertEquals(hashCalculator.calculateSHA256(topLevelFile),
                snapshot.entries().get(topLevelFile.toAbsolutePath().normalize().toString()));
        assertEquals(hashCalculator.calculateSHA256(nestedFile),
                snapshot.entries().get(nestedFile.toAbsolutePath().normalize().toString()));
    }

    @Test
    void baselineSnapshotDefensivelyCopiesAndValidatesEntries() throws Exception {
        Path monitored = temporaryDirectory.resolve("monitored").toAbsolutePath().normalize();
        Path file = monitored.resolve("sample.txt");
        java.util.HashMap<String, String> mutableEntries = new java.util.HashMap<>();
        mutableEntries.put(file.toString(),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

        BaselineSnapshot snapshot = new BaselineSnapshot(monitored, mutableEntries);
        mutableEntries.clear();

        assertEquals(1, snapshot.entryCount());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.entries().put(file.toString(),
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertThrows(IllegalArgumentException.class,
                () -> new BaselineSnapshot(monitored, Map.of(file.toString(), "not-a-hash")));
        assertThrows(IllegalArgumentException.class,
                () -> new BaselineSnapshot(monitored, Map.of(
                        temporaryDirectory.resolve("outside.txt").toString(),
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")));
    }

    @Test
    void preparationRejectsMissingAndNonDirectoryRoots() throws Exception {
        BaselineManager manager = new BaselineManager();
        Path missing = temporaryDirectory.resolve("missing");
        Path file = temporaryDirectory.resolve("not-a-directory.txt");
        Files.writeString(file, "data");

        assertThrows(IOException.class, () -> manager.prepareBaseline(missing));
        assertThrows(IOException.class, () -> manager.prepareBaseline(file));
    }

    @Test
    void successfulPreparationDoesNotChangeActiveBaselineOrBaselineFiles()
            throws Exception {
        Path monitored = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Path sample = monitored.resolve("sample.txt");
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");
        Path backupFile = temporaryDirectory.resolve("baseline.txt.bak");
        Files.writeString(sample, "initial");

        BaselineManager manager = new BaselineManager();
        manager.createBaseline(monitored);
        manager.saveBaselineToFile(baselineFile);
        Files.writeString(sample, "saved version");
        manager.createBaseline(monitored);
        manager.saveBaselineToFile(baselineFile);
        Map<String, String> activeEntries = manager.getBaselineEntries();
        byte[] baselineBefore = Files.readAllBytes(baselineFile);
        byte[] backupBefore = Files.readAllBytes(backupFile);

        Files.writeString(sample, "candidate version");
        BaselineSnapshot candidate = manager.prepareBaseline(monitored);

        assertEquals(new HashCalculator().calculateSHA256(sample),
                candidate.entries().get(sample.toAbsolutePath().normalize().toString()));
        assertEquals(activeEntries, manager.getBaselineEntries());
        assertArrayEquals(baselineBefore, Files.readAllBytes(baselineFile));
        assertArrayEquals(backupBefore, Files.readAllBytes(backupFile));
    }

    @Test
    void hashFailureDuringPreparationPreservesActiveBaselineAndBothFiles()
            throws Exception {
        Path monitored = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Path sample = monitored.resolve("sample.txt");
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");
        Path backupFile = temporaryDirectory.resolve("baseline.txt.bak");
        Files.writeString(sample, "initial");

        BaselineManager manager = new BaselineManager(new FailingHashCalculator("failure.txt"));
        manager.createBaseline(monitored);
        manager.saveBaselineToFile(baselineFile);
        Files.writeString(sample, "saved version");
        manager.createBaseline(monitored);
        manager.saveBaselineToFile(baselineFile);
        Map<String, String> activeEntries = manager.getBaselineEntries();
        byte[] baselineBefore = Files.readAllBytes(baselineFile);
        byte[] backupBefore = Files.readAllBytes(backupFile);
        Files.writeString(monitored.resolve("failure.txt"), "trigger hash failure");

        assertThrows(IOException.class, () -> manager.prepareBaseline(monitored));

        assertEquals(activeEntries, manager.getBaselineEntries());
        assertArrayEquals(baselineBefore, Files.readAllBytes(baselineFile));
        assertArrayEquals(backupBefore, Files.readAllBytes(backupFile));
    }

    private static final class FailingHashCalculator extends HashCalculator {

        private final String failingFileName;

        private FailingHashCalculator(String failingFileName) {
            this.failingFileName = failingFileName;
        }

        @Override
        public String calculateSHA256(Path filePath) throws IOException {
            if (filePath.getFileName().toString().equals(failingFileName)) {
                throw new IOException("Injected hash read failure");
            }
            return super.calculateSHA256(filePath);
        }
    }

    private static final class TrackingIntegrityChecker extends IntegrityChecker {
        private boolean hashComparisonCalled;
        private boolean getCurrentHashCalled;

        @Override
        public boolean isHashModified(String currentHash, String originalHash) {
            hashComparisonCalled = true;
            return super.isHashModified(currentHash, originalHash);
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
