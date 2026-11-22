package kz.kaznaru.fim.desktop.controller;

import kz.kaznaru.fim.application.BackgroundTaskService;
import kz.kaznaru.fim.application.BaselineStatus;
import kz.kaznaru.fim.desktop.bootstrap.DesktopServiceContext;
import kz.kaznaru.fim.desktop.navigation.DisposableScreenController;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Displays read-only baseline status from the application service. */
public final class BaselineManagementController implements DisposableScreenController {

    private final DesktopServiceContext serviceContext;

    @FXML
    private Label statusBadge;
    @FXML
    private Label baselinePathValue;
    @FXML
    private Label backupStatusValue;
    @FXML
    private Label protectedFilesValue;
    @FXML
    private Label monitoredDirectoryValue;
    @FXML
    private Label recoveryInfo;
    @FXML
    private Label loadingLabel;
    @FXML
    private Label errorLabel;
    @FXML
    private ProgressIndicator loadingIndicator;
    @FXML
    private Button refreshButton;
    @FXML
    private VBox statusDetails;

    private BackgroundTaskService.TaskHandle<BaselineStatus> activeTask;
    private long queryGeneration;
    private boolean hasLoadedStatus;
    private volatile boolean disposed;

    public BaselineManagementController(DesktopServiceContext serviceContext) {
        this.serviceContext = Objects.requireNonNull(serviceContext, "serviceContext");
    }

    @FXML
    private void initialize() {
        refreshStatus();
    }

    @FXML
    private void refreshStatus() {
        if (disposed) {
            return;
        }

        long generation = ++queryGeneration;
        if (activeTask != null && !activeTask.isDone()) {
            activeTask.cancel();
        }

        setLoading(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        activeTask = serviceContext.backgroundTaskService()
                .submit(serviceContext::baselineStatus);
        activeTask.completion().whenComplete((status, failure) ->
                runOnFxThread(() -> finishStatusQuery(generation, status, failure)));
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        queryGeneration++;
        if (activeTask != null && !activeTask.isDone()) {
            activeTask.cancel();
        }
        activeTask = null;
    }

    private void finishStatusQuery(
            long generation,
            BaselineStatus status,
            Throwable failure) {
        if (disposed || generation != queryGeneration) {
            return;
        }
        activeTask = null;
        setLoading(false);

        if (failure != null) {
            showError(failure);
            return;
        }

        render(status);
        hasLoadedStatus = true;
    }

    private void render(BaselineStatus status) {
        statusBadge.setText(status.state().name());
        statusBadge.getStyleClass().removeAll(
                "baseline-status-loading",
                "baseline-status-not-created",
                "baseline-status-active",
                "baseline-status-recovered");
        statusBadge.getStyleClass().add(switch (status.state()) {
            case NOT_CREATED -> "baseline-status-not-created";
            case ACTIVE -> "baseline-status-active";
            case RECOVERED -> "baseline-status-recovered";
        });

        baselinePathValue.setText(status.baselineFile().toString());
        baselinePathValue.setTooltip(new Tooltip(status.baselineFile().toString()));
        backupStatusValue.setText(status.backupFileExists() ? "Present" : "Not present");
        protectedFilesValue.setText(Integer.toString(status.entryCount()));
        monitoredDirectoryValue.setText(status.monitoredDirectory().toString());
        monitoredDirectoryValue.setTooltip(
                new Tooltip(status.monitoredDirectory().toString()));

        recoveryInfo.setVisible(status.recoveredFromBackup());
        recoveryInfo.setManaged(status.recoveredFromBackup());
        statusDetails.setVisible(true);
        statusDetails.setManaged(true);
    }

    private void showError(Throwable failure) {
        errorLabel.setText("Could not retrieve baseline status: " + describeFailure(failure));
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
        if (!hasLoadedStatus) {
            statusBadge.setText("Unavailable");
            statusBadge.getStyleClass().removeAll(
                    "baseline-status-loading",
                    "baseline-status-not-created",
                    "baseline-status-active",
                    "baseline-status-recovered");
            statusBadge.getStyleClass().add("baseline-status-loading");
        }
    }

    private void setLoading(boolean loading) {
        loadingIndicator.setVisible(loading);
        loadingIndicator.setManaged(loading);
        loadingLabel.setText(hasLoadedStatus
                ? "Refreshing baseline status..."
                : "Loading baseline status...");
        loadingLabel.setVisible(loading);
        loadingLabel.setManaged(loading);
        refreshButton.setDisable(disposed);
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
}
