package kz.kaznaru.fim.desktop;

import kz.kaznaru.fim.desktop.bootstrap.DesktopServiceContext;
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
    private static final String STARTUP_VIEW =
            "/kz/kaznaru/fim/desktop/view/startup.fxml";

    private DesktopServiceContext serviceContext;

    @Override
    public void start(Stage primaryStage) throws IOException, SQLException {
        DesktopServiceContext initializedContext = new DesktopServiceContext();
        try {
            URL viewResource = Objects.requireNonNull(
                    FimDesktopApplication.class.getResource(STARTUP_VIEW),
                    "Desktop startup FXML was not found: " + STARTUP_VIEW);
            Parent root = FXMLLoader.load(viewResource);

            primaryStage.setTitle(
                    "FIM SENTINEL — Cybersecurity Operations Center");
            primaryStage.setMinWidth(MINIMUM_WIDTH);
            primaryStage.setMinHeight(MINIMUM_HEIGHT);
            primaryStage.setScene(new Scene(root, INITIAL_WIDTH, INITIAL_HEIGHT));
            primaryStage.setWidth(INITIAL_WIDTH);
            primaryStage.setHeight(INITIAL_HEIGHT);
            primaryStage.show();
            serviceContext = initializedContext;
        } catch (IOException | RuntimeException exception) {
            initializedContext.close();
            throw exception;
        }
    }

    @Override
    public void stop() {
        if (serviceContext != null) {
            serviceContext.close();
            serviceContext = null;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
