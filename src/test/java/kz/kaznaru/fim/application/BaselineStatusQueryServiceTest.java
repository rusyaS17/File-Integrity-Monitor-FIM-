package kz.kaznaru.fim.application;

import kz.kaznaru.fim.core.BaselineManager;
import kz.kaznaru.fim.desktop.bootstrap.DesktopServiceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class BaselineStatusQueryServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsNotCreatedWhenNoPrimaryOrBackupExists() throws Exception {
        ApplicationConfiguration configuration = configuration();
        try (DesktopServiceContext context = new DesktopServiceContext(configuration)) {
            BaselineStatus status = context.baselineStatus();

            assertFalse(status.primaryFileExists());
            assertFalse(status.backupFileExists());
            assertEquals(configuration.baselineFile().toAbsolutePath().normalize(),
                    status.baselineFile());
            assertEquals(configuration.monitoredDirectory().toAbsolutePath().normalize(),
                    status.monitoredDirectory());
            assertEquals(0, status.entryCount());
            assertFalse(status.recoveredFromBackup());
            assertEquals(BaselineStatus.State.NOT_CREATED, status.state());
        }
    }

    @Test
    void reportsAnActivePrimaryBaselineAndItsEntryCount() throws Exception {
        ApplicationConfiguration configuration = configuration();
        createMonitoredFile(configuration);
        BaselineManager manager = new BaselineManager();
        manager.createBaseline(configuration.monitoredDirectory());
        manager.saveBaselineToFile(configuration.baselineFile());

        try (DesktopServiceContext context = new DesktopServiceContext(configuration)) {
            BaselineStatus status = context.baselineStatus();

            assertTrue(status.primaryFileExists());
            assertFalse(status.backupFileExists());
            assertEquals(1, status.entryCount());
            assertFalse(status.recoveredFromBackup());
            assertEquals(BaselineStatus.State.ACTIVE, status.state());
        }
    }

    @Test
    void reportsAnExistingBackupWithoutTreatingItAsRequiredForActiveStatus()
            throws Exception {
        ApplicationConfiguration configuration = configuration();
        createBaselineWithBackup(configuration);

        try (DesktopServiceContext context = new DesktopServiceContext(configuration)) {
            BaselineStatus status = context.baselineStatus();

            assertTrue(status.primaryFileExists());
            assertTrue(status.backupFileExists());
            assertEquals(1, status.entryCount());
            assertEquals(BaselineStatus.State.ACTIVE, status.state());
        }
    }

    @Test
    void reportsRecoveredStateAndRepeatedQueriesDoNotChangeBaselineFiles()
            throws Exception {
        ApplicationConfiguration configuration = configuration();
        createBaselineWithBackup(configuration);
        Files.writeString(configuration.baselineFile(), "invalid baseline data");

        try (DesktopServiceContext context = new DesktopServiceContext(configuration)) {
            Path baselineFile = configuration.baselineFile();
            Path backupFile = baselineFile.resolveSibling(
                    baselineFile.getFileName() + BaselineManager.BACKUP_SUFFIX);
            byte[] baselineBytes = Files.readAllBytes(baselineFile);
            byte[] backupBytes = Files.readAllBytes(backupFile);
            FileTime baselineModified = Files.getLastModifiedTime(baselineFile);
            FileTime backupModified = Files.getLastModifiedTime(backupFile);

            BaselineStatus first = context.baselineStatus();
            BaselineStatus second = context.baselineStatus();

            assertTrue(first.primaryFileExists());
            assertTrue(first.backupFileExists());
            assertEquals(1, first.entryCount());
            assertTrue(first.recoveredFromBackup());
            assertEquals(BaselineStatus.State.RECOVERED, first.state());
            assertEquals(first, second);
            assertArrayEquals(baselineBytes, Files.readAllBytes(baselineFile));
            assertArrayEquals(backupBytes, Files.readAllBytes(backupFile));
            assertEquals(baselineModified, Files.getLastModifiedTime(baselineFile));
            assertEquals(backupModified, Files.getLastModifiedTime(backupFile));
        }
    }

    private void createBaselineWithBackup(ApplicationConfiguration configuration)
            throws Exception {
        createMonitoredFile(configuration);
        BaselineManager manager = new BaselineManager();
        manager.createBaseline(configuration.monitoredDirectory());
        manager.saveBaselineToFile(configuration.baselineFile());
        Files.writeString(
                configuration.monitoredDirectory().resolve("sample.txt"), "updated");
        manager.createBaseline(configuration.monitoredDirectory());
        manager.saveBaselineToFile(configuration.baselineFile());
    }

    private void createMonitoredFile(ApplicationConfiguration configuration)
            throws Exception {
        Files.createDirectories(configuration.monitoredDirectory());
        Files.writeString(
                configuration.monitoredDirectory().resolve("sample.txt"), "original");
    }

    private ApplicationConfiguration configuration() {
        return ApplicationConfiguration.forDirectory(
                temporaryDirectory, Duration.ofMillis(100), Duration.ofSeconds(5));
    }
}
