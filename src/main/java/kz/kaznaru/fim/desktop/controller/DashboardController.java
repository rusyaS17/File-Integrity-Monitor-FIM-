package kz.kaznaru.fim.desktop.controller;

import kz.kaznaru.fim.application.BackgroundTaskService;
import kz.kaznaru.fim.application.DashboardSnapshot;
import kz.kaznaru.fim.application.RootUpdatedEvent;
import kz.kaznaru.fim.database.FileEventRecord;
import kz.kaznaru.fim.desktop.bootstrap.DesktopServiceContext;
import kz.kaznaru.fim.desktop.component.StatusBadgeCell;
import kz.kaznaru.fim.desktop.navigation.DisposableScreenController;
import kz.kaznaru.fim.desktop.viewmodel.ActivityChartMapper;
import kz.kaznaru.fim.desktop.viewmodel.ActivityChartPoint;
import kz.kaznaru.fim.model.FileStatus;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.Circle;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Provides the dashboard screen's real-data queries and monitoring controls. */
public final class DashboardController implements DisposableScreenController {

    private static final java.time.Duration ACTIVITY_RANGE = java.time.Duration.ofHours(24);
    private static final java.time.Duration ACTIVITY_BUCKET = java.time.Duration.ofHours(1);
    private static final int RECENT_EVENT_LIMIT = 10;

    private static final DateTimeFormatter EVENT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, HH:mm:ss", Locale.ENGLISH)
                    .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter REFRESH_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, HH:mm:ss", Locale.ENGLISH)
                    .withZone(ZoneId.systemDefault());

    private final DesktopServiceContext serviceContext;
    private final Runnable monitoringStateChanged;
    private final Consumer<RootUpdatedEvent> rootUpdatedListener = this::onRootUpdated;
    private final Consumer<Throwable> monitoringErrorListener = this::onMonitoringError;

    @FXML
    private Label monitoredRootValue;
    @FXML
    private Label lastRefreshValue;
    @FXML
    private Label totalFilesValue;
    @FXML
    private Label protectedFilesValue;
    @FXML
    private Label modifiedFilesValue;
    @FXML
    private Label newFilesValue;
    @FXML
    private Label deletedFilesValue;
    @FXML
    private Label threatsErrorsValue;
    @FXML
    private Button refreshButton;
    @FXML
    private ProgressIndicator dashboardProgress;
    @FXML
    private Label dashboardErrorLabel;
    @FXML
    private LineChart<String, Number> activityChart;
    @FXML
    private Label activityEmptyState;
    @FXML
    private TableView<FileEventRecord> recentEventsTable;
    @FXML
    private TableColumn<FileEventRecord, String> timeColumn;
    @FXML
    private TableColumn<FileEventRecord, String> filePathColumn;
    @FXML
    private TableColumn<FileEventRecord, String> eventTypeColumn;
    @FXML
    private TableColumn<FileEventRecord, FileStatus> statusColumn;
    @FXML
    private Label recentEventsEmptyState;
    @FXML
    private Label monitoringStateValue;
    @FXML
    private Circle monitoringOverviewIndicator;
    @FXML
    private Button startMonitoringButton;
    @FXML
    private Button stopMonitoringButton;
    @FXML
    private ProgressIndicator monitoringProgress;
    @FXML
    private Label monitoringErrorLabel;

    private XYChart.Series<String, Number> activitySeries;
    private String monitoredRoot;
    private boolean refreshInProgress;
    private boolean refreshPending;
    private boolean monitoringOperationInProgress;
    private boolean monitoringErrorActive;
    private boolean hasSuccessfulDashboardLoad;
    private long refreshGeneration;
    private BackgroundTaskService.TaskHandle<DashboardSnapshot> activeDashboardTask;
    private volatile boolean disposed;

    public DashboardController(
            DesktopServiceContext serviceContext,
            Runnable monitoringStateChanged) {
        this.serviceContext = Objects.requireNonNull(serviceContext, "serviceContext");
        this.monitoringStateChanged = Objects.requireNonNull(
                monitoringStateChanged, "monitoringStateChanged");
    }

    @FXML
    private void initialize() {
        monitoredRoot = serviceContext.configuration()
                .monitoredDirectory()
                .toAbsolutePath()
                .normalize()
                .toString();
        monitoredRootValue.setText(monitoredRoot);
        monitoredRootValue.setTooltip(new Tooltip(monitoredRoot));
        lastRefreshValue.setText("Not refreshed yet");

        initializeRecentEventsTable();
        activitySeries = new XYChart.Series<>();
        activitySeries.setName("File events");
        activityChart.setData(FXCollections.observableArrayList(activitySeries));
        activityChart.setAnimated(false);
        activityChart.setLegendVisible(false);

        serviceContext.addRootUpdatedListener(rootUpdatedListener);
        serviceContext.addMonitoringErrorListener(monitoringErrorListener);
        updateMonitoringState();
        requestDashboardRefresh();
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        serviceContext.removeRootUpdatedListener(rootUpdatedListener);
        serviceContext.removeMonitoringErrorListener(monitoringErrorListener);
        if (activeDashboardTask != null && !activeDashboardTask.isDone()) {
            activeDashboardTask.cancel();
        }
    }

    @FXML
    private void refreshDashboard() {
        requestDashboardRefresh();
    }

    @FXML
    private void startMonitoring() {
        changeMonitoringState(true);
    }

    @FXML
    private void stopMonitoring() {
        changeMonitoringState(false);
    }

    private void initializeRecentEventsTable() {
        timeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                EVENT_TIME_FORMAT.format(Instant.parse(cell.getValue().occurredAtUtc()))));
        filePathColumn.setCellValueFactory(
                cell -> new ReadOnlyStringWrapper(cell.getValue().filePath()));
        filePathColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String filePath, boolean empty) {
                super.updateItem(filePath, empty);
                setText(empty ? null : filePath);
                setTooltip(empty || filePath == null ? null : new Tooltip(filePath));
            }
        });
        eventTypeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                cell.getValue().eventType().name()));
        statusColumn.setCellValueFactory(
                cell -> new ReadOnlyObjectWrapper<>(cell.getValue().eventType()));
        statusColumn.setCellFactory(column -> new StatusBadgeCell());
        recentEventsTable.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private void requestDashboardRefresh() {
        if (disposed) {
            return;
        }
        if (refreshInProgress) {
            refreshPending = true;
            return;
        }

        refreshInProgress = true;
        refreshButton.setDisable(true);
        dashboardProgress.setVisible(true);
        dashboardProgress.setManaged(true);
        dashboardErrorLabel.setVisible(false);
        dashboardErrorLabel.setManaged(false);

        long requestGeneration = ++refreshGeneration;
        Instant toExclusive = Instant.now();
        Instant fromInclusive = toExclusive.minus(ACTIVITY_RANGE);
        activeDashboardTask = serviceContext.queryDashboardAsync(
                fromInclusive, toExclusive, ACTIVITY_BUCKET, RECENT_EVENT_LIMIT);
        activeDashboardTask.completion().whenComplete((snapshot, failure) ->
                runOnFxThread(() -> finishDashboardRefresh(
                        requestGeneration, snapshot, failure)));
    }

    private void finishDashboardRefresh(
            long requestGeneration,
            DashboardSnapshot snapshot,
            Throwable failure) {
        if (disposed || requestGeneration != refreshGeneration) {
            return;
        }
        refreshInProgress = false;
        activeDashboardTask = null;
        refreshButton.setDisable(false);
        dashboardProgress.setVisible(false);
        dashboardProgress.setManaged(false);

        boolean repeatForPendingUpdates = refreshPending;
        refreshPending = false;
        if (!repeatForPendingUpdates) {
            if (failure == null) {
                renderDashboard(snapshot);
                hasSuccessfulDashboardLoad = true;
                lastRefreshValue.setText(REFRESH_TIME_FORMAT.format(snapshot.generatedAtUtc()));
            } else {
                showDashboardError(failure);
                if (!hasSuccessfulDashboardLoad) {
                    activityEmptyState.setText("Dashboard activity is unavailable.");
                    recentEventsEmptyState.setText("Dashboard events are unavailable.");
                }
            }
        }
        if (repeatForPendingUpdates) {
            requestDashboardRefresh();
        }
    }

    private void renderDashboard(DashboardSnapshot snapshot) {
        var counts = snapshot.fileStatusCounts();
        totalFilesValue.setText(Long.toString(counts.totalFiles()));
        protectedFilesValue.setText(Long.toString(counts.count(FileStatus.SAFE)));
        modifiedFilesValue.setText(Long.toString(counts.count(FileStatus.MODIFIED)));
        newFilesValue.setText(Long.toString(counts.count(FileStatus.NEW)));
        deletedFilesValue.setText(Long.toString(counts.count(FileStatus.DELETED)));
        threatsErrorsValue.setText(Long.toString(
                counts.count(FileStatus.MODIFIED) + counts.count(FileStatus.ERROR)));

        List<ActivityChartPoint> points = ActivityChartMapper.toLocalPoints(
                snapshot.activityBuckets(), ZoneId.systemDefault());
        activitySeries.getData().setAll(points.stream()
                .map(point -> new XYChart.Data<String, Number>(
                        point.localTimeLabel(), point.eventCount()))
                .toList());
        boolean hasActivity = !points.isEmpty();
        activityChart.setVisible(hasActivity);
        activityChart.setManaged(hasActivity);
        activityEmptyState.setVisible(!hasActivity);
        activityEmptyState.setManaged(!hasActivity);
        if (!hasActivity) {
            activityEmptyState.setText("No persisted events in the last 24 hours.");
        }

        List<FileEventRecord> events = snapshot.recentEvents();
        recentEventsTable.getItems().setAll(events);
        recentEventsTable.setVisible(!events.isEmpty());
        recentEventsTable.setManaged(!events.isEmpty());
        recentEventsEmptyState.setVisible(events.isEmpty());
        recentEventsEmptyState.setManaged(events.isEmpty());
        if (events.isEmpty()) {
            recentEventsEmptyState.setText("No persisted events found.");
        }
        dashboardErrorLabel.setVisible(false);
        dashboardErrorLabel.setManaged(false);
    }

    private void showDashboardError(Throwable failure) {
        dashboardErrorLabel.setText("Dashboard refresh failed: " + describeFailure(failure));
        dashboardErrorLabel.setVisible(true);
        dashboardErrorLabel.setManaged(true);
    }

    private void changeMonitoringState(boolean start) {
        if (disposed || monitoringOperationInProgress) {
            return;
        }
        monitoringErrorActive = false;
        monitoringErrorLabel.setText("");
        monitoringErrorLabel.setVisible(false);
        monitoringErrorLabel.setManaged(false);
        monitoringOperationInProgress = true;
        updateMonitoringState();

        BackgroundTaskService.TaskHandle<Void> task = start
                ? serviceContext.startMonitoringAsync()
                : serviceContext.stopMonitoringAsync();
        task.completion().whenComplete((ignored, failure) -> runOnFxThread(() -> {
            if (disposed) {
                return;
            }
            monitoringOperationInProgress = false;
            if (failure != null) {
                displayMonitoringError(failure);
            }
            updateMonitoringState();
            monitoringStateChanged.run();
            requestDashboardRefresh();
        }));
    }

    private void updateMonitoringState() {
        boolean running = serviceContext.monitoringService().isRunning();
        monitoringStateValue.setText(running ? "RUNNING" : "STOPPED");
        String indicatorClass = monitoringErrorActive
                ? "indicator-critical"
                : running ? "indicator-ready" : "indicator-idle";
        setIndicatorState(monitoringOverviewIndicator, indicatorClass);

        startMonitoringButton.setDisable(monitoringOperationInProgress || running);
        stopMonitoringButton.setDisable(monitoringOperationInProgress || !running);
        monitoringProgress.setVisible(monitoringOperationInProgress);
        monitoringProgress.setManaged(monitoringOperationInProgress);
    }

    private void onRootUpdated(RootUpdatedEvent event) {
        if (event.monitorRoot().equals(monitoredRoot)) {
            runOnFxThread(this::requestDashboardRefresh);
        }
    }

    private void onMonitoringError(Throwable failure) {
        runOnFxThread(() -> displayMonitoringError(failure));
    }

    private void displayMonitoringError(Throwable failure) {
        monitoringErrorActive = true;
        monitoringErrorLabel.setText("Monitoring error: " + describeFailure(failure));
        monitoringErrorLabel.setVisible(true);
        monitoringErrorLabel.setManaged(true);
        updateMonitoringState();
        monitoringStateChanged.run();
    }

    private void runOnFxThread(Runnable action) {
        if (disposed) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        try {
            Platform.runLater(() -> {
                if (!disposed) {
                    action.run();
                }
            });
        } catch (IllegalStateException exception) {
            if (!disposed) {
                throw exception;
            }
        }
    }

    private static String describeFailure(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message;
    }

    private void setIndicatorState(Circle indicator, String stateClass) {
        indicator.getStyleClass().removeAll(
                "indicator-ready",
                "indicator-idle",
                "indicator-connected",
                "indicator-warning",
                "indicator-critical");
        indicator.getStyleClass().add(stateClass);
    }
}
