package kz.kaznaru.fim.desktop.navigation;

import kz.kaznaru.fim.application.ApplicationConfiguration;
import kz.kaznaru.fim.desktop.bootstrap.DesktopServiceContext;
import kz.kaznaru.fim.desktop.controller.BaselineManagementController;
import kz.kaznaru.fim.desktop.controller.DashboardController;
import kz.kaznaru.fim.desktop.controller.FileScannerController;
import kz.kaznaru.fim.desktop.controller.MainShellController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

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
                assertEquals(
                        configuration.baselineFile().toAbsolutePath().normalize().toString(),
                        ((javafx.scene.control.Label)
                                controllerField(baselineController, "baselinePathValue")).getText());

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
