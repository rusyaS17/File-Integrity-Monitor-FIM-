package kz.kaznaru.fim.desktop.bootstrap;

import kz.kaznaru.fim.application.ApplicationConfiguration;
import kz.kaznaru.fim.application.RootUpdatedEvent;
import kz.kaznaru.fim.application.DashboardSnapshot;
import kz.kaznaru.fim.core.BaselineManager;
import kz.kaznaru.fim.database.DatabaseManager;
import kz.kaznaru.fim.desktop.controller.MainShellController;
import kz.kaznaru.fim.model.FileStatus;
import javafx.fxml.FXML;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;

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
    void runsScansDashboardQueriesAndMonitoringLifecycleAsynchronously() throws Exception {
        ApplicationConfiguration configuration = configuration();
        Files.createDirectories(configuration.monitoredDirectory());
        Files.writeString(configuration.monitoredDirectory().resolve("sample.txt"), "new file");
        try (DesktopServiceContext context = new DesktopServiceContext(configuration)) {
            var updates = new CopyOnWriteArrayList<RootUpdatedEvent>();
            context.addRootUpdatedListener(updates::add);

            var scan = context.scanAsync();
            var report = scan.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1, report.count(FileStatus.NEW));
            assertEquals(1, updates.size());

            DashboardSnapshot snapshot = context.queryDashboardAsync(
                            Instant.EPOCH,
                            Instant.now().plus(Duration.ofDays(1)),
                            Duration.ofHours(1),
                            10)
                    .completion()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals(1, snapshot.fileStatusCounts().count(FileStatus.NEW));
            assertEquals(1, snapshot.recentEvents().size());
            assertEquals(1, snapshot.activityBuckets().stream()
                    .mapToLong(bucket -> bucket.totalEvents())
                    .sum());

            context.startMonitoringAsync().completion()
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(context.monitoringService().isRunning());
            context.stopMonitoringAsync().completion()
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertFalse(context.monitoringService().isRunning());
        }
    }

    @Test
    void mainShellFxmlContainsNavigationAndDashboardSections() throws Exception {
        var resource = getClass().getResource(
                "/kz/kaznaru/fim/desktop/view/main-shell.fxml");
        assertNotNull(resource);
        String fxml = Files.readString(Path.of(resource.toURI()));
        assertTrue(fxml.contains("FIM SENTINEL"));
        assertTrue(fxml.contains("fx:controller=\"kz.kaznaru.fim.desktop.controller.MainShellController\""));
        assertTrue(fxml.contains("Dashboard"));
        assertTrue(fxml.contains("File Monitoring"));
        assertTrue(fxml.contains("File Scanner"));
        assertTrue(fxml.contains("Event History"));
        assertTrue(fxml.contains("Baseline Management"));
        assertTrue(fxml.contains("Settings"));
        assertTrue(fxml.contains("Not refreshed yet"));
        assertTrue(fxml.contains("fx:id=\"totalFilesValue\""));
        assertTrue(fxml.contains("fx:id=\"threatsErrorsValue\""));
        assertTrue(fxml.contains("LineChart"));
        assertTrue(fxml.contains("fx:id=\"recentEventsTable\""));
        assertTrue(fxml.contains("fx:id=\"startMonitoringButton\""));
        assertTrue(fxml.contains("prefColumns=\"3\""));
        assertTrue(fxml.contains("prefTileWidth=\"280.0\""));
        assertFalse(fxml.contains("minTileWidth="));
        assertFalse(fxml.contains("maxTileWidth="));
        assertFalse(fxml.contains("columnResizePolicy="));
        assertFalse(fxml.contains("Dashboard data is not connected yet"));
        assertControllerBindings(fxml);
        assertNotNull(getClass().getResource(
                "/kz/kaznaru/fim/desktop/css/fim-sentinel.css"));
        try (var input = resource.openStream()) {
            DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(input);
        }
    }

    private void assertControllerBindings(String fxml) throws Exception {
        Matcher idMatcher = Pattern.compile("fx:id=\"([^\"]+)\"").matcher(fxml);
        while (idMatcher.find()) {
            Field field = MainShellController.class.getDeclaredField(idMatcher.group(1));
            assertTrue(field.isAnnotationPresent(FXML.class),
                    () -> "FXML field is missing @FXML: " + field.getName());
        }

        Matcher actionMatcher = Pattern.compile("onAction=\"#([^\"]+)\"").matcher(fxml);
        while (actionMatcher.find()) {
            Method method = MainShellController.class.getDeclaredMethod(actionMatcher.group(1));
            assertTrue(method.isAnnotationPresent(FXML.class),
                    () -> "FXML handler is missing @FXML: " + method.getName());
        }
    }

    private ApplicationConfiguration configuration() {
        return ApplicationConfiguration.forDirectory(
                temporaryDirectory, Duration.ofMillis(100), Duration.ofSeconds(5));
    }
}
