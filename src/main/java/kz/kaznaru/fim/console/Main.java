package kz.kaznaru.fim.console;

import kz.kaznaru.fim.application.ApplicationConfiguration;
import kz.kaznaru.fim.application.EventProcessor;
import kz.kaznaru.fim.application.MonitoringService;
import kz.kaznaru.fim.application.ScanPersistenceService;
import kz.kaznaru.fim.core.BaselineManager;
import kz.kaznaru.fim.core.DirectoryScanner;
import kz.kaznaru.fim.database.DatabaseManager;
import kz.kaznaru.fim.model.FileScanResult;
import kz.kaznaru.fim.model.FileStatus;
import kz.kaznaru.fim.model.ScanReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;

public class Main {

    public static void main(String[] args) {
        try {
            ApplicationConfiguration configuration = ApplicationConfiguration.defaults();
            Path monitoredFolder = configuration.monitoredDirectory();
            Path baselineFile = configuration.baselineFile();

            Files.createDirectories(monitoredFolder);
            DatabaseManager databaseManager = new DatabaseManager(configuration.databaseFile());
            databaseManager.initializeSchema();
            ScanPersistenceService persistenceService =
                    new ScanPersistenceService(databaseManager);
            BaselineManager baselineManager = new BaselineManager();
            String command = args.length == 0 ? "scan" : args[0].toLowerCase();

            System.out.println("========================================");
            System.out.println("       FILE INTEGRITY MONITOR");
            System.out.println("========================================");
            System.out.println("Monitored folder: " + monitoredFolder.toAbsolutePath());

            if (command.equals("init")) {
                baselineManager.createBaseline(monitoredFolder);
                baselineManager.saveBaselineToFile(baselineFile);
                persistInitialScan(monitoredFolder, baselineManager, persistenceService);
                System.out.println("BASELINE CREATED SUCCESSFULLY");
                System.out.println("Files recorded: " + baselineManager.getBaselineEntries().size());
                System.out.println("Run the application again to scan for changes.");
                return;
            }

            if (command.equals("monitor")) {
                monitor(monitoredFolder, baselineFile, baselineManager, persistenceService,
                        configuration);
                return;
            }

            if (!command.equals("scan")) {
                System.out.println("Unknown command. Use: init, scan, or monitor");
                return;
            }

            if (!Files.exists(baselineFile)) {
                baselineManager.createBaseline(monitoredFolder);
                baselineManager.saveBaselineToFile(baselineFile);
                persistInitialScan(monitoredFolder, baselineManager, persistenceService);
                System.out.println("No baseline was found. A new baseline has been created.");
                System.out.println("Change or add a file in the monitored folder, then run again.");
                return;
            }

            baselineManager.loadBaselineFromFile(baselineFile, monitoredFolder);
            if (baselineManager.wasRecoveredFromBackup()) {
                System.out.println("WARNING: The primary baseline was invalid or missing.");
                System.out.println("Recovered and restored a valid baseline backup.");
            }
            DirectoryScanner scanner = new DirectoryScanner();
            ScanReport report = scanner.scan(monitoredFolder,
                    baselineManager.getBaselineEntries());
            persistenceService.persist(monitoredFolder, report);
            printReport(report);

        } catch (Exception exception) {
            System.err.println("ERROR: " + exception.getMessage());
        }
    }

    private static void persistInitialScan(
            Path monitoredFolder,
            BaselineManager baselineManager,
            ScanPersistenceService persistenceService) throws IOException, SQLException {
        ScanReport report = new DirectoryScanner().scan(
                monitoredFolder, baselineManager.getBaselineEntries());
        persistenceService.persist(monitoredFolder, report);
    }

    private static void monitor(
            Path monitoredFolder,
            Path baselineFile,
            BaselineManager baselineManager,
            ScanPersistenceService persistenceService,
            ApplicationConfiguration configuration)
            throws IOException, SQLException, InterruptedException {
        if (Files.exists(baselineFile)) {
            baselineManager.loadBaselineFromFile(baselineFile, monitoredFolder);
            if (baselineManager.wasRecoveredFromBackup()) {
                System.out.println("WARNING: Recovered the baseline from its backup.");
            }
        } else {
            baselineManager.createBaseline(monitoredFolder);
            baselineManager.saveBaselineToFile(baselineFile);
            persistInitialScan(monitoredFolder, baselineManager, persistenceService);
            System.out.println("No baseline was found. A new baseline has been created.");
        }

        DirectoryScanner scanner = new DirectoryScanner();
        EventProcessor eventProcessor = new EventProcessor(
                monitoredFolder,
                baselineManager.getBaselineEntries(),
                scanner,
                persistenceService);
        CountDownLatch shutdownRequested = new CountDownLatch(1);

        try (MonitoringService monitoringService = new MonitoringService(
                monitoredFolder,
                eventProcessor,
                configuration.watchDebounceDuration(),
                configuration.reconciliationInterval())) {
            monitoringService.start();
            Thread shutdownHook = new Thread(() -> {
                monitoringService.stop();
                shutdownRequested.countDown();
            }, "fim-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                System.out.println("Real-time monitoring started. Press Ctrl+C to stop.");
                shutdownRequested.await();
            } finally {
                monitoringService.stop();
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // The JVM is already running shutdown hooks.
                }
            }
        }
    }

    private static void printReport(ScanReport report) {
        System.out.println("SCAN REPORT");
        System.out.println("----------------------------------------");
        for (FileScanResult result : report.results()) {
            String relativePath;
            try {
                relativePath = Path.of(".").toAbsolutePath().normalize()
                        .relativize(result.path().toAbsolutePath().normalize()).toString();
            } catch (IllegalArgumentException exception) {
                relativePath = result.path().toString();
            }
            System.out.printf("[%-8s] %s%n", result.status(), relativePath);
        }
        System.out.println("----------------------------------------");
        System.out.println("Total:    " + report.totalFiles());
        System.out.println("SAFE:     " + report.count(FileStatus.SAFE));
        System.out.println("MODIFIED: " + report.count(FileStatus.MODIFIED));
        System.out.println("NEW:      " + report.count(FileStatus.NEW));
        System.out.println("DELETED:  " + report.count(FileStatus.DELETED));
        System.out.println("ERROR:    " + report.count(FileStatus.ERROR));
        System.out.println("========================================");
        System.out.println("Note: baseline is not changed during a scan.");
        System.out.println("Use 'init' only when you intentionally want a new baseline.");
    }
}
