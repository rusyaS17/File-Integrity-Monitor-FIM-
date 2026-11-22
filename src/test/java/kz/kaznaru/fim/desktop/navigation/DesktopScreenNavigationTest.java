package kz.kaznaru.fim.desktop.navigation;

import kz.kaznaru.fim.application.ApplicationConfiguration;
import kz.kaznaru.fim.core.BaselineManager;
import kz.kaznaru.fim.desktop.bootstrap.DesktopServiceContext;
import kz.kaznaru.fim.desktop.controller.BaselineManagementController;
import kz.kaznaru.fim.desktop.controller.DashboardController;
import kz.kaznaru.fim.desktop.controller.FileScannerController;
import kz.kaznaru.fim.desktop.controller.MainShellController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DesktopScreenNavigationTest {

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void startJavaFxToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                started.countDown();
            });
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }
        assertTrue(started.await(10, TimeUnit.SECONDS),
                "JavaFX toolkit did not start");
    }

    @AfterAll
    static void stopJavaFxToolkit() {
        Platform.exit();
    }

    @Test
    void loadsDashboardAndSwitchesContentBetweenAllEnabledDestinations()
            throws Exception {
        ApplicationConfiguration configuration = ApplicationConfiguration.forDirectory(
                temporaryDirectory, Duration.ofMillis(100), Duration.ofSeconds(5));
        Files.createDirectories(configuration.monitoredDirectory());
        Files.writeString(configuration.monitoredDirectory().resolve("sample.txt"), "scanned");
        try (DesktopServiceContext context = new DesktopServiceContext(configuration)) {
            context.scanAsync().completion().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            CountDownLatch dashboardDataLoaded = new CountDownLatch(1);
            ShellHandles handles = runOnFxThread(() -> {
                FXMLLoader shellLoader = new FXMLLoader(getClass().getResource(
                        "/kz/kaznaru/fim/desktop/view/main-shell.fxml"));
                shellLoader.setControllerFactory(type -> {
                    if (type == MainShellController.class) {
                        return new MainShellController(context);
                    }
                    throw new IllegalArgumentException("Unexpected shell controller " + type);
                });
                shellLoader.load();
                MainShellController shellController = shellLoader.getController();
                StackPane contentHost =
                        (StackPane) shellLoader.getNamespace().get("contentHost");

                assertEquals(NavigationDestination.DASHBOARD,
                        shellController.activeDestination());
                assertEquals(NavigationDestination.DASHBOARD,
                        shellController.navigationState().activeDestination());
                DashboardController dashboardController = assertInstanceOf(
                        DashboardController.class, shellController.activeScreenController());
                assertEquals(1, contentHost.getChildren().size());
                Parent dashboardRoot = (Parent) contentHost.getChildren().get(0);
                javafx.scene.control.Label totalFilesLabel =
                        (javafx.scene.control.Label)
                                controllerField(dashboardController, "totalFilesValue");
                totalFilesLabel.textProperty().addListener((observable, oldValue, newValue) -> {
                    if ("1".equals(newValue)) {
                        dashboardDataLoaded.countDown();
                    }
                });
                if ("1".equals(totalFilesLabel.getText())) {
                    dashboardDataLoaded.countDown();
                }
                assertNotNull(controllerField(dashboardController, "recentEventsTable"));
                return new ShellHandles(shellLoader, shellController, contentHost);
            });

            assertTrue(dashboardDataLoaded.await(5, TimeUnit.SECONDS),
                    "Dashboard did not render the persisted scan result");
            runOnFxThread(() -> {
                FXMLLoader shellLoader = handles.loader();
                MainShellController shellController = handles.controller();
                StackPane contentHost = handles.contentHost();
                Object databaseManager = context.databaseManager();
                DashboardController dashboardController = assertInstanceOf(
                        DashboardController.class, shellController.activeScreenController());
                assertEquals("1", ((javafx.scene.control.Label)
                        controllerField(dashboardController, "totalFilesValue")).getText());
                assertEquals("1", ((javafx.scene.control.Label)
                        controllerField(dashboardController, "newFilesValue")).getText());
                Parent dashboardRoot = (Parent) contentHost.getChildren().get(0);

                Button scannerButton =
                        (Button) shellLoader.getNamespace().get("scannerButton");
                scannerButton.fire();
                assertEquals(NavigationDestination.FILE_SCANNER,
                        shellController.activeDestination());
                assertEquals(NavigationDestination.FILE_SCANNER,
                        shellController.navigationState().activeDestination());
                FileScannerController scannerController = assertInstanceOf(
                        FileScannerController.class, shellController.activeScreenController());
                assertEquals(1, contentHost.getChildren().size());
                Parent scannerRoot = (Parent) contentHost.getChildren().get(0);
                assertNotSame(dashboardRoot, scannerRoot);
                assertEquals(
                        configuration.monitoredDirectory().toAbsolutePath().normalize().toString(),
                        ((javafx.scene.control.Label)
                                controllerField(scannerController, "monitoredRootValue")).getText());

                Button baselineButton =
                        (Button) shellLoader.getNamespace().get("baselineButton");
                baselineButton.fire();
                assertEquals(NavigationDestination.BASELINE_MANAGEMENT,
                        shellController.activeDestination());
                assertEquals(NavigationDestination.BASELINE_MANAGEMENT,
                        shellController.navigationState().activeDestination());
                BaselineManagementController baselineController = assertInstanceOf(
                        BaselineManagementController.class,
                        shellController.activeScreenController());
                assertEquals(1, contentHost.getChildren().size());
                Parent baselineRoot = (Parent) contentHost.getChildren().get(0);
                assertNotSame(scannerRoot, baselineRoot);
                assertNotNull(baselineController);

                ((Button) shellLoader.getNamespace().get("dashboardButton")).fire();
                assertEquals(NavigationDestination.DASHBOARD,
                        shellController.activeDestination());
                assertEquals(NavigationDestination.DASHBOARD,
                        shellController.navigationState().activeDestination());
                assertInstanceOf(DashboardController.class,
                        shellController.activeScreenController());
                assertEquals(1, contentHost.getChildren().size());
                assertNotSame(baselineRoot, contentHost.getChildren().get(0));
                assertSame(databaseManager, context.databaseManager());
                ((ScrollPane) contentHost.getChildren().get(0)).setVvalue(1.0);
                return null;
            });
            runOnFxThread(() -> {
                ScrollPane dashboardScrollPane =
                        (ScrollPane) handles.contentHost().getChildren().get(0);
                assertEquals(dashboardScrollPane.getVmin(), dashboardScrollPane.getVvalue(),
                        "A newly navigated Dashboard must open at the top");
                handles.controller().dispose();
                return null;
            });
        }
    }

    @Test
    void baselineScreenRendersNotCreatedAndRefreshesOnTheFxThread() throws Exception {
        ApplicationConfiguration configuration = configuration();
        try (DesktopServiceContext context = new DesktopServiceContext(configuration)) {
            BaselineScreenHandles handles = loadBaselineScreen(context);
            try {
                awaitText(handles.statusBadge(), "NOT_CREATED");
                runOnFxThread(() -> {
                    assertTrue(handles.statusBadge().getStyleClass()
                            .contains("baseline-status-not-created"));
                    assertEquals("Not present", handles.backupStatusValue().getText());
                    assertEquals("0", handles.protectedFilesValue().getText());
                    assertEquals(
                            configuration.baselineFile().toAbsolutePath().normalize().toString(),
                            handles.baselinePathValue().getText());
                    assertEquals(configuration.monitoredDirectory()
                                    .toAbsolutePath().normalize().toString(),
                            handles.monitoredDirectoryValue().getText());
                    return null;
                });

                AtomicBoolean fxThreadOnly = new AtomicBoolean(true);
                CountDownLatch refreshFinished = new CountDownLatch(1);
                AtomicBoolean loadingObserved = new AtomicBoolean();
                runOnFxThread(() -> {
                    handles.statusBadge().textProperty().addListener((observable, oldValue, value) ->
                            fxThreadOnly.compareAndSet(
                                    true, Platform.isFxApplicationThread()));
                    handles.loadingIndicator().visibleProperty().addListener(
                            (observable, oldValue, visible) -> {
                                fxThreadOnly.compareAndSet(
                                        true, Platform.isFxApplicationThread());
                                if (visible) {
                                    loadingObserved.set(true);
                                } else if (loadingObserved.get()) {
                                    refreshFinished.countDown();
                                }
                            });
                    handles.refreshButton().fire();
                    return null;
                });

                assertTrue(refreshFinished.await(5, TimeUnit.SECONDS),
                        "Refresh did not complete");
                runOnFxThread(() -> {
                    assertTrue(fxThreadOnly.get(),
                            "Baseline UI changed outside the FX thread");
                    assertEquals("NOT_CREATED", handles.statusBadge().getText());
                    return null;
                });
                assertFalse(Files.exists(configuration.baselineFile()));
                assertFalse(Files.exists(configuration.baselineFile()
                        .resolveSibling("baseline.txt.bak")));
            } finally {
                dispose(handles);
            }
        }
    }

    @Test
    void baselineScreenRendersActiveStatusAndBackupAvailability() throws Exception {
        ApplicationConfiguration configuration = configuration();
        createMonitoredFile(configuration);
        BaselineManager manager = new BaselineManager();
        manager.createBaseline(configuration.monitoredDirectory());
        manager.saveBaselineToFile(configuration.baselineFile());
        try (DesktopServiceContext context = new DesktopServiceContext(configuration)) {
            BaselineScreenHandles handles = loadBaselineScreen(context);
            try {
                awaitText(handles.statusBadge(), "ACTIVE");
                runOnFxThread(() -> {
                    assertTrue(handles.statusBadge().getStyleClass()
                            .contains("baseline-status-active"));
                    assertEquals("Not present", handles.backupStatusValue().getText());
                    assertEquals("1", handles.protectedFilesValue().getText());
                    return null;
                });
                assertTrue(handles.updatesOnFxThread().get(),
                        "Baseline status was rendered outside the FX thread");
                assertTrue(Files.isRegularFile(configuration.baselineFile()));
                assertFalse(Files.exists(configuration.baselineFile()
                        .resolveSibling("baseline.txt.bak")));
            } finally {
                dispose(handles);
            }
        }
    }

    @Test
    void baselineScreenRendersRecoveredStatusWithoutChangingBaselineFilesOnRefresh()
            throws Exception {
        ApplicationConfiguration configuration = configuration();
        createBaselineWithBackup(configuration);
        Files.writeString(configuration.baselineFile(), "invalid baseline data");

        try (DesktopServiceContext context = new DesktopServiceContext(configuration)) {
            Path baselineFile = configuration.baselineFile();
            Path backupFile = baselineFile.resolveSibling("baseline.txt.bak");
            byte[] baselineBytes = Files.readAllBytes(baselineFile);
            byte[] backupBytes = Files.readAllBytes(backupFile);
            FileTime baselineModified = Files.getLastModifiedTime(baselineFile);
            FileTime backupModified = Files.getLastModifiedTime(backupFile);

            BaselineScreenHandles handles = loadBaselineScreen(context);
            try {
                awaitText(handles.statusBadge(), "RECOVERED");
                runOnFxThread(() -> {
                    assertTrue(handles.statusBadge().getStyleClass()
                            .contains("baseline-status-recovered"));
                    assertTrue(handles.recoveryInfo().isVisible());
                    assertEquals("Present", handles.backupStatusValue().getText());
                    assertEquals("1", handles.protectedFilesValue().getText());
                    return null;
                });
                assertTrue(handles.updatesOnFxThread().get(),
                        "Recovered status was rendered outside the FX thread");

                refreshAndAwait(handles);

                runOnFxThread(() -> {
                    assertEquals("RECOVERED", handles.statusBadge().getText());
                    return null;
                });
                assertArrayEquals(baselineBytes, Files.readAllBytes(baselineFile));
                assertArrayEquals(backupBytes, Files.readAllBytes(backupFile));
                assertEquals(baselineModified, Files.getLastModifiedTime(baselineFile));
                assertEquals(backupModified, Files.getLastModifiedTime(backupFile));
            } finally {
                dispose(handles);
            }
        }
    }

    @Test
    void baselineScreenShowsQueryErrorsAndCanBeRefreshed() throws Exception {
        DesktopServiceContext context = new DesktopServiceContext(configuration());
        BaselineScreenHandles handles = loadBaselineScreen(context);
        try {
            awaitText(handles.statusBadge(), "NOT_CREATED");
            context.close();

            runOnFxThread(() -> {
                handles.refreshButton().fire();
                return null;
            });

            awaitVisible(handles.errorLabel());
            runOnFxThread(() -> {
                assertTrue(handles.errorLabel().getText()
                        .startsWith("Could not retrieve baseline status:"));
                assertEquals("NOT_CREATED", handles.statusBadge().getText());
                return null;
            });
        } finally {
            dispose(handles);
            context.close();
        }
    }

    private BaselineScreenHandles loadBaselineScreen(DesktopServiceContext context)
            throws Exception {
        return runOnFxThread(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/kz/kaznaru/fim/desktop/view/baseline-management.fxml"));
            loader.setControllerFactory(type -> {
                if (type == BaselineManagementController.class) {
                    return new BaselineManagementController(context);
                }
                throw new IllegalArgumentException("Unexpected controller " + type);
            });
            Parent root = loader.load();
            BaselineManagementController controller = loader.getController();
            AtomicBoolean updatesOnFxThread = new AtomicBoolean(true);
            Label statusBadge = (Label) controllerField(controller, "statusBadge");
            Label baselinePathValue = (Label) controllerField(controller, "baselinePathValue");
            Label backupStatusValue = (Label) controllerField(controller, "backupStatusValue");
            Label protectedFilesValue =
                    (Label) controllerField(controller, "protectedFilesValue");
            Label monitoredDirectoryValue =
                    (Label) controllerField(controller, "monitoredDirectoryValue");
            Label recoveryInfo = (Label) controllerField(controller, "recoveryInfo");
            Label errorLabel = (Label) controllerField(controller, "errorLabel");
            for (Label label : new Label[] {
                    statusBadge,
                    baselinePathValue,
                    backupStatusValue,
                    protectedFilesValue,
                    monitoredDirectoryValue,
                    recoveryInfo,
                    errorLabel}) {
                watchFxThreadUpdates(label, updatesOnFxThread);
            }
            ProgressIndicator loadingIndicator =
                    (ProgressIndicator) controllerField(controller, "loadingIndicator");
            loadingIndicator.visibleProperty().addListener(
                    (observable, oldValue, visible) -> updatesOnFxThread.compareAndSet(
                            true, Platform.isFxApplicationThread()));
            return new BaselineScreenHandles(
                    controller,
                    statusBadge,
                    baselinePathValue,
                    backupStatusValue,
                    protectedFilesValue,
                    monitoredDirectoryValue,
                    recoveryInfo,
                    errorLabel,
                    loadingIndicator,
                    (Button) controllerField(controller, "refreshButton"),
                    updatesOnFxThread);
        });
    }

    private static void watchFxThreadUpdates(Label label, AtomicBoolean updatesOnFxThread) {
        label.textProperty().addListener((observable, oldValue, newValue) ->
                updatesOnFxThread.compareAndSet(
                        true, Platform.isFxApplicationThread()));
        label.visibleProperty().addListener((observable, oldValue, visible) ->
                updatesOnFxThread.compareAndSet(
                        true, Platform.isFxApplicationThread()));
    }

    private static void awaitText(Label label, String expected) throws Exception {
        CountDownLatch changed = new CountDownLatch(1);
        runOnFxThread(() -> {
            if (expected.equals(label.getText())) {
                changed.countDown();
            } else {
                label.textProperty().addListener((observable, oldValue, value) -> {
                    if (expected.equals(value)) {
                        changed.countDown();
                    }
                });
            }
            return null;
        });
        boolean updated = changed.await(5, TimeUnit.SECONDS);
        String actual = runOnFxThread(label::getText);
        assertTrue(updated, "Expected label text: " + expected + ", got: " + actual);
        runOnFxThread(() -> null);
    }

    private static void awaitVisible(Label label) throws Exception {
        CountDownLatch visible = new CountDownLatch(1);
        runOnFxThread(() -> {
            if (label.isVisible()) {
                visible.countDown();
            } else {
                label.visibleProperty().addListener((observable, oldValue, newValue) -> {
                    if (newValue) {
                        visible.countDown();
                    }
                });
            }
            return null;
        });
        assertTrue(visible.await(5, TimeUnit.SECONDS), "Expected error state to be visible");
        runOnFxThread(() -> null);
    }

    private static void refreshAndAwait(BaselineScreenHandles handles) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean loadingObserved = new AtomicBoolean();
        runOnFxThread(() -> {
            handles.loadingIndicator().visibleProperty().addListener(
                    (observable, oldValue, visible) -> {
                        if (visible) {
                            loadingObserved.set(true);
                        } else if (loadingObserved.get()) {
                            completed.countDown();
                        }
                    });
            handles.refreshButton().fire();
            return null;
        });
        assertTrue(completed.await(5, TimeUnit.SECONDS), "Refresh did not complete");
        runOnFxThread(() -> null);
    }

    private static void dispose(BaselineScreenHandles handles) throws Exception {
        runOnFxThread(() -> {
            handles.controller().dispose();
            return null;
        });
    }

    private ApplicationConfiguration configuration() {
        return ApplicationConfiguration.forDirectory(
                temporaryDirectory, Duration.ofMillis(100), Duration.ofSeconds(5));
    }

    private void createMonitoredFile(ApplicationConfiguration configuration)
            throws Exception {
        Files.createDirectories(configuration.monitoredDirectory());
        Files.writeString(configuration.monitoredDirectory().resolve("sample.txt"), "trusted");
    }

    private void createBaselineWithBackup(ApplicationConfiguration configuration)
            throws Exception {
        createMonitoredFile(configuration);
        BaselineManager manager = new BaselineManager();
        manager.createBaseline(configuration.monitoredDirectory());
        manager.saveBaselineToFile(configuration.baselineFile());
        Files.writeString(configuration.monitoredDirectory().resolve("sample.txt"), "updated");
        manager.createBaseline(configuration.monitoredDirectory());
        manager.saveBaselineToFile(configuration.baselineFile());
    }

    private record BaselineScreenHandles(
            BaselineManagementController controller,
            Label statusBadge,
            Label baselinePathValue,
            Label backupStatusValue,
            Label protectedFilesValue,
            Label monitoredDirectoryValue,
            Label recoveryInfo,
            Label errorLabel,
            ProgressIndicator loadingIndicator,
            Button refreshButton,
            AtomicBoolean updatesOnFxThread) {
    }

    private record ShellHandles(
            FXMLLoader loader,
            MainShellController controller,
            StackPane contentHost) {
    }

    private static <T> T runOnFxThread(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    private static Object controllerField(Object controller, String name) throws Exception {
        var field = controller.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }
}
