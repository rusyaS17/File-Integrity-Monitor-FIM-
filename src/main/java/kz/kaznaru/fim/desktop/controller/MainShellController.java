package kz.kaznaru.fim.desktop.controller;

import kz.kaznaru.fim.application.RootUpdatedEvent;
import kz.kaznaru.fim.desktop.bootstrap.DesktopControllerFactory;
import kz.kaznaru.fim.desktop.bootstrap.DesktopServiceContext;
import kz.kaznaru.fim.desktop.navigation.DesktopScreenCatalog;
import kz.kaznaru.fim.desktop.navigation.NavigationController;
import kz.kaznaru.fim.desktop.navigation.NavigationDestination;
import kz.kaznaru.fim.desktop.navigation.NavigationState;
import kz.kaznaru.fim.desktop.navigation.ScreenNavigator;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Owns the persistent application shell and delegates screen content. */
public final class MainShellController {

    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM");

    private final DesktopServiceContext serviceContext;
    private final Consumer<RootUpdatedEvent> rootUpdatedListener = event ->
            runOnFxThread(this::refreshStatus);
    private final Consumer<Throwable> monitoringErrorListener = failure ->
            runOnFxThread(() -> refreshStatus(true));

    @FXML
    private Button dashboardButton;
    @FXML
    private Button monitoringButton;
    @FXML
    private Button scannerButton;
    @FXML
    private Button historyButton;
    @FXML
    private Button baselineButton;
    @FXML
    private Button settingsButton;
    @FXML
    private Label systemStatusValue;
    @FXML
    private Label monitoringStatusValue;
    @FXML
    private Label databaseStatusValue;
    @FXML
    private Circle systemStatusIndicator;
    @FXML
    private Circle monitoringStatusIndicator;
    @FXML
    private Circle databaseStatusIndicator;
    @FXML
    private Label localClockValue;
    @FXML
    private Label localDateValue;
    @FXML
    private StackPane contentHost;

    private Timeline clockTimeline;
    private NavigationController navigationController;
    private ScreenNavigator screenNavigator;
    private volatile boolean disposed;
    private boolean monitoringErrorActive;

    public MainShellController(DesktopServiceContext serviceContext) {
        this.serviceContext = Objects.requireNonNull(serviceContext, "serviceContext");
    }

    @FXML
    private void initialize() {
        NavigationState navigationState = new NavigationState();
        screenNavigator = new ScreenNavigator(
                contentHost,
                new DesktopScreenCatalog(),
                new DesktopControllerFactory(
                        serviceContext, this::monitoringStateChanged)::create);
        navigationController = new NavigationController(
                navigationState,
                Map.of(
                        NavigationDestination.DASHBOARD, dashboardButton,
                        NavigationDestination.FILE_MONITORING, monitoringButton,
                        NavigationDestination.FILE_SCANNER, scannerButton,
                        NavigationDestination.EVENT_HISTORY, historyButton,
                        NavigationDestination.BASELINE_MANAGEMENT, baselineButton,
                        NavigationDestination.SETTINGS, settingsButton),
                this::navigateTo);
        navigationController.initialize();

        systemStatusValue.setText("READY");
        setIndicatorState(systemStatusIndicator, "indicator-ready");
        databaseStatusValue.setText("READY");
        setIndicatorState(databaseStatusIndicator, "indicator-connected");
        refreshStatus();

        serviceContext.addRootUpdatedListener(rootUpdatedListener);
        serviceContext.addMonitoringErrorListener(monitoringErrorListener);

        updateClock();
        clockTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateClock()));
        clockTimeline.setCycleCount(Animation.INDEFINITE);
        clockTimeline.play();

        navigateTo(NavigationDestination.DASHBOARD);
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        serviceContext.removeRootUpdatedListener(rootUpdatedListener);
        serviceContext.removeMonitoringErrorListener(monitoringErrorListener);
        if (clockTimeline != null) {
            clockTimeline.stop();
            clockTimeline = null;
        }
        if (screenNavigator != null) {
            screenNavigator.close();
            screenNavigator = null;
        }
    }

    public NavigationState navigationState() {
        return navigationController == null ? null : navigationController.state();
    }

    public NavigationDestination activeDestination() {
        return screenNavigator == null ? null : screenNavigator.activeDestination();
    }

    public Object activeScreenController() {
        return screenNavigator == null ? null : screenNavigator.activeController();
    }

    private void navigateTo(NavigationDestination destination) {
        if (disposed) {
            return;
        }
        try {
            screenNavigator.navigate(destination);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load desktop screen: " + destination.label(), exception);
        }
        refreshStatus();
    }

    private void monitoringStateChanged() {
        runOnFxThread(this::refreshStatus);
    }

    private void refreshStatus() {
        refreshStatus(false);
    }

    private void refreshStatus(boolean monitoringError) {
        if (disposed) {
            return;
        }
        monitoringErrorActive |= monitoringError;
        boolean running = serviceContext.monitoringService().isRunning();
        monitoringStatusValue.setText(
                monitoringErrorActive ? "ATTENTION" : running ? "RUNNING" : "STOPPED");
        setIndicatorState(
                monitoringStatusIndicator,
                monitoringErrorActive
                        ? "indicator-warning"
                        : running ? "indicator-ready" : "indicator-idle");
    }

    private void runOnFxThread(Runnable action) {
        if (disposed) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
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
    }

    private void updateClock() {
        LocalDateTime now = LocalDateTime.now();
        localClockValue.setText(CLOCK_FORMAT.format(now));
        localDateValue.setText(DATE_FORMAT.format(now));
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
