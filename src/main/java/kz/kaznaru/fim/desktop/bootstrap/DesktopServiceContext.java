package kz.kaznaru.fim.desktop.bootstrap;

import kz.kaznaru.fim.application.ApplicationConfiguration;
import kz.kaznaru.fim.application.BaselineStatus;
import kz.kaznaru.fim.application.BaselineStatusQueryService;
import kz.kaznaru.fim.application.BackgroundTaskService;
import kz.kaznaru.fim.application.DashboardQueryService;
import kz.kaznaru.fim.application.DashboardSnapshot;
import kz.kaznaru.fim.application.EventProcessor;
import kz.kaznaru.fim.application.MonitoringService;
import kz.kaznaru.fim.application.RootUpdatedEvent;
import kz.kaznaru.fim.application.ScanPersistenceService;
import kz.kaznaru.fim.core.BaselineManager;
import kz.kaznaru.fim.core.DirectoryScanner;
import kz.kaznaru.fim.database.DatabaseManager;
import kz.kaznaru.fim.model.ScanReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/** Creates and owns the services shared by the desktop presentation layer. */
public final class DesktopServiceContext implements AutoCloseable {

    private final ApplicationConfiguration configuration;
    private final DatabaseManager databaseManager;
    private final BaselineManager baselineManager;
    private final BaselineStatusQueryService baselineStatusQueryService;
    private final DirectoryScanner directoryScanner;
    private final ScanPersistenceService persistenceService;
    private final DashboardQueryService dashboardQueryService;
    private final BackgroundTaskService backgroundTaskService;
    private final EventProcessor eventProcessor;
    private final MonitoringService monitoringService;
    private volatile boolean closed;

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
        BaselineStatusQueryService initializedBaselineStatusQueryService =
                new BaselineStatusQueryService(configuration, initializedBaselineManager);

        DirectoryScanner initializedDirectoryScanner = new DirectoryScanner();
        ScanPersistenceService initializedPersistenceService =
                new ScanPersistenceService(initializedDatabaseManager);
        DashboardQueryService initializedDashboardQueryService =
                new DashboardQueryService(initializedDatabaseManager);
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
        BackgroundTaskService initializedBackgroundTaskService =
                new BackgroundTaskService();

        databaseManager = initializedDatabaseManager;
        baselineManager = initializedBaselineManager;
        baselineStatusQueryService = initializedBaselineStatusQueryService;
        directoryScanner = initializedDirectoryScanner;
        persistenceService = initializedPersistenceService;
        dashboardQueryService = initializedDashboardQueryService;
        backgroundTaskService = initializedBackgroundTaskService;
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

    public BaselineStatusQueryService baselineStatusQueryService() {
        return baselineStatusQueryService;
    }

    public BaselineStatus baselineStatus() {
        ensureOpen();
        return baselineStatusQueryService.query();
    }

    public DirectoryScanner directoryScanner() {
        return directoryScanner;
    }

    public ScanPersistenceService persistenceService() {
        return persistenceService;
    }

    public DashboardQueryService dashboardQueryService() {
        return dashboardQueryService;
    }

    public BackgroundTaskService backgroundTaskService() {
        return backgroundTaskService;
    }

    public EventProcessor eventProcessor() {
        return eventProcessor;
    }

    public MonitoringService monitoringService() {
        return monitoringService;
    }

    public void addRootUpdatedListener(Consumer<RootUpdatedEvent> listener) {
        ensureOpen();
        persistenceService.addRootUpdatedListener(listener);
    }

    public boolean removeRootUpdatedListener(Consumer<RootUpdatedEvent> listener) {
        return persistenceService.removeRootUpdatedListener(listener);
    }

    public void addMonitoringErrorListener(Consumer<Throwable> listener) {
        ensureOpen();
        monitoringService.addErrorListener(listener);
    }

    public boolean removeMonitoringErrorListener(Consumer<Throwable> listener) {
        return monitoringService.removeErrorListener(listener);
    }

    public BackgroundTaskService.TaskHandle<DashboardSnapshot> queryDashboardAsync(
            Instant fromInclusive,
            Instant toExclusive,
            Duration bucketSize,
            int recentEventLimit) {
        ensureOpen();
        return backgroundTaskService.submit(() -> dashboardQueryService.query(
                configuration.monitoredDirectory(),
                fromInclusive,
                toExclusive,
                bucketSize,
                recentEventLimit));
    }

    public BackgroundTaskService.TaskHandle<ScanReport> scanAsync() {
        ensureOpen();
        return backgroundTaskService.submit(() -> {
            ensureOpen();
            Path root = configuration.monitoredDirectory();
            ScanReport report = directoryScanner.scan(
                    root, baselineManager.getBaselineEntries());
            persistenceService.persist(root, report);
            return report;
        });
    }

    public BackgroundTaskService.TaskHandle<Void> startMonitoringAsync() {
        ensureOpen();
        return backgroundTaskService.submit(() -> {
            ensureOpen();
            monitoringService.start();
            return null;
        });
    }

    public BackgroundTaskService.TaskHandle<Void> stopMonitoringAsync() {
        ensureOpen();
        return backgroundTaskService.submit(monitoringService::stop);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        monitoringService.close();
        backgroundTaskService.close();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Desktop service context is closed");
        }
    }
}
