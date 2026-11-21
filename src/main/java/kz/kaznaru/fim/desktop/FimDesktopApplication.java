package kz.kaznaru.fim.desktop;

import kz.kaznaru.fim.desktop.bootstrap.DesktopServiceContext;
import kz.kaznaru.fim.desktop.controller.MainShellController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.sql.SQLException;
import java.util.Objects;

/** JavaFX entry point for the FIM SENTINEL desktop application. */
public final class FimDesktopApplication extends Application {

    private static final double INITIAL_WIDTH = 1440;
    private static final double INITIAL_HEIGHT = 900;
    private static final double MINIMUM_WIDTH = 1100;
    private static final double MINIMUM_HEIGHT = 700;
    private static final String MAIN_SHELL_VIEW =
            "/kz/kaznaru/fim/desktop/view/main-shell.fxml";
    private static final String DESIGN_STYLESHEET =
            "/kz/kaznaru/fim/desktop/css/fim-sentinel.css";

    private DesktopServiceContext serviceContext;
    private MainShellController shellController;

    @Override
    public void start(Stage primaryStage) throws IOException, SQLException {
        DesktopServiceContext initializedContext = new DesktopServiceContext();
        FXMLLoader loader = null;
        try {
            URL viewResource = Objects.requireNonNull(
                    FimDesktopApplication.class.getResource(MAIN_SHELL_VIEW),
                    "Desktop main shell FXML was not found: " + MAIN_SHELL_VIEW);
            URL stylesheet = Objects.requireNonNull(
                    FimDesktopApplication.class.getResource(DESIGN_STYLESHEET),
                    "Desktop stylesheet was not found: " + DESIGN_STYLESHEET);

            loader = new FXMLLoader(viewResource);
            loader.setControllerFactory(controllerType -> {
                if (controllerType == MainShellController.class) {
                    return new MainShellController(initializedContext);
                }
                throw new IllegalStateException(
                        "Unsupported desktop controller: " + controllerType.getName());
            });
            Parent root = loader.load();
            MainShellController initializedController = loader.getController();

            primaryStage.setTitle(
                    "FIM SENTINEL — Cybersecurity Operations Center");
            primaryStage.setMinWidth(MINIMUM_WIDTH);
            primaryStage.setMinHeight(MINIMUM_HEIGHT);
            Scene scene = new Scene(root, INITIAL_WIDTH, INITIAL_HEIGHT);
            scene.getStylesheets().add(stylesheet.toExternalForm());
            primaryStage.setScene(scene);
            primaryStage.setWidth(INITIAL_WIDTH);
            primaryStage.setHeight(INITIAL_HEIGHT);
            primaryStage.show();
            serviceContext = initializedContext;
            shellController = initializedController;
        } catch (IOException | RuntimeException exception) {
            if (loader != null && loader.getController() instanceof MainShellController controller) {
                controller.dispose();
            }
            initializedContext.close();
            throw exception;
        }
    }

    @Override
    public void stop() {
        if (shellController != null) {
            shellController.dispose();
            shellController = null;
        }
        if (serviceContext != null) {
            serviceContext.close();
            serviceContext = null;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
