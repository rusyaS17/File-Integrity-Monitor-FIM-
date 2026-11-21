package kz.kaznaru.fim.desktop.bootstrap;

import kz.kaznaru.fim.application.ApplicationConfiguration;
import kz.kaznaru.fim.core.BaselineManager;
import kz.kaznaru.fim.database.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class DesktopServiceContextTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesExistingServicesAndDatabaseWithoutCreatingBaseline() throws Exception {
        ApplicationConfiguration configuration = configuration();

        DesktopServiceContext context = new DesktopServiceContext(configuration);
        try {
            assertEquals(configuration, context.configuration());
            assertTrue(Files.isDirectory(configuration.monitoredDirectory()));
            assertTrue(Files.isRegularFile(configuration.databaseFile()));
            assertFalse(Files.exists(configuration.baselineFile()));
            assertFalse(context.monitoringService().isRunning());
            assertNotNull(context.directoryScanner());
            assertNotNull(context.eventProcessor());

            boolean monitoredFilesTableExists =
                    context.databaseManager().withConnection(connection -> {
                        try (var statement = connection.prepareStatement(
                                "SELECT name FROM sqlite_master WHERE type = 'table' "
                                        + "AND name = 'monitored_files'");
                             var resultSet = statement.executeQuery()) {
                            return resultSet.next();
                        }
                    });
            assertTrue(monitoredFilesTableExists);
        } finally {
            context.close();
            context.close();
        }
    }

    @Test
    void loadsExistingBaselineWithoutChangingItsContents() throws Exception {
        ApplicationConfiguration configuration = configuration();
        Files.createDirectories(configuration.monitoredDirectory());
        Path monitoredFile = configuration.monitoredDirectory().resolve("sample.txt");
        Files.writeString(monitoredFile, "trusted");

        BaselineManager baselineManager = new BaselineManager();
        baselineManager.createBaseline(configuration.monitoredDirectory());
        baselineManager.saveBaselineToFile(configuration.baselineFile());
        byte[] baselineBefore = Files.readAllBytes(configuration.baselineFile());

        try (DesktopServiceContext context = new DesktopServiceContext(configuration)) {
            assertEquals(baselineManager.getBaselineEntries(),
                    context.baselineManager().getBaselineEntries());
            assertArrayEquals(baselineBefore,
                    Files.readAllBytes(configuration.baselineFile()));
        }
    }

    @Test
    void closesAnActiveMonitoringService() throws Exception {
        DesktopServiceContext context = new DesktopServiceContext(configuration());
        context.monitoringService().start();

        context.close();

        assertFalse(context.monitoringService().isRunning());
    }

    @Test
    void startupFxmlContainsRequiredVerificationText() throws Exception {
        var resource = getClass().getResource(
                "/kz/kaznaru/fim/desktop/view/startup.fxml");
        assertNotNull(resource);
        String fxml = Files.readString(Path.of(resource.toURI()));
        assertTrue(fxml.contains("FIM SENTINEL"));
        assertTrue(fxml.contains("CYBERSECURITY OPERATIONS CENTER"));
    }

    private ApplicationConfiguration configuration() {
        return ApplicationConfiguration.forDirectory(
                temporaryDirectory, Duration.ofMillis(100), Duration.ofSeconds(5));
    }
}
