package kz.kaznaru.fim.desktop.bootstrap;

import kz.kaznaru.fim.application.ApplicationConfiguration;
import kz.kaznaru.fim.application.EventProcessor;
import kz.kaznaru.fim.application.MonitoringService;
import kz.kaznaru.fim.application.ScanPersistenceService;
import kz.kaznaru.fim.core.BaselineManager;
import kz.kaznaru.fim.core.DirectoryScanner;
import kz.kaznaru.fim.database.DatabaseManager;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Objects;

/** Creates and owns the services shared by the desktop presentation layer. */
public final class DesktopServiceContext implements AutoCloseable {

    private final ApplicationConfiguration configuration;
    private final DatabaseManager databaseManager;
    private final BaselineManager baselineManager;
    private final DirectoryScanner directoryScanner;
    private final ScanPersistenceService persistenceService;
    private final EventProcessor eventProcessor;
    private final MonitoringService monitoringService;
    private boolean closed;

    public DesktopServiceContext() throws IOException, SQLException {
        this(ApplicationConfiguration.defaults());
    }

    public DesktopServiceContext(ApplicationConfiguration configuration)
            throws IOException, SQLException {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        Files.createDirectories(configuration.monitoredDirectory());

        DatabaseManager initializedDatabaseManager =
                new DatabaseManager(configuration.databaseFile());
        initializedDatabaseManager.initializeSchema();

        BaselineManager initializedBaselineManager = new BaselineManager();
        if (Files.exists(configuration.baselineFile())) {
            initializedBaselineManager.loadBaselineFromFile(
                    configuration.baselineFile(), configuration.monitoredDirectory());
        }

        DirectoryScanner initializedDirectoryScanner = new DirectoryScanner();
        ScanPersistenceService initializedPersistenceService =
                new ScanPersistenceService(initializedDatabaseManager);
        EventProcessor initializedEventProcessor = new EventProcessor(
                configuration.monitoredDirectory(),
                initializedBaselineManager.getBaselineEntries(),
                initializedDirectoryScanner,
                initializedPersistenceService);
        MonitoringService initializedMonitoringService = new MonitoringService(
                configuration.monitoredDirectory(),
                initializedEventProcessor,
                configuration.watchDebounceDuration(),
                configuration.reconciliationInterval());

        databaseManager = initializedDatabaseManager;
        baselineManager = initializedBaselineManager;
        directoryScanner = initializedDirectoryScanner;
        persistenceService = initializedPersistenceService;
        eventProcessor = initializedEventProcessor;
        monitoringService = initializedMonitoringService;
    }

    public ApplicationConfiguration configuration() {
        return configuration;
    }

    public DatabaseManager databaseManager() {
        return databaseManager;
    }

    public BaselineManager baselineManager() {
        return baselineManager;
    }

    public DirectoryScanner directoryScanner() {
        return directoryScanner;
    }

    public ScanPersistenceService persistenceService() {
        return persistenceService;
    }

    public EventProcessor eventProcessor() {
        return eventProcessor;
    }

    public MonitoringService monitoringService() {
        return monitoringService;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        monitoringService.close();
    }
}
