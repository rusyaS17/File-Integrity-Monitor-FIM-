package kz.kaznaru.fim.desktop.navigation;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import java.util.function.Function;

/** Loads destination FXML into a stable host while disposing the prior screen. */
public final class ScreenNavigator implements AutoCloseable {

    private final StackPane contentHost;
    private final DesktopScreenCatalog screenCatalog;
    private final Function<Class<?>, Object> controllerFactory;

    private NavigationDestination activeDestination;
    private Object activeController;

    public ScreenNavigator(
            StackPane contentHost,
            DesktopScreenCatalog screenCatalog,
            Function<Class<?>, Object> controllerFactory) {
        this.contentHost = Objects.requireNonNull(contentHost, "contentHost");
        this.screenCatalog = Objects.requireNonNull(screenCatalog, "screenCatalog");
        this.controllerFactory = Objects.requireNonNull(
                controllerFactory, "controllerFactory");
    }

    public void navigate(NavigationDestination destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        if (!destination.isAvailable()) {
            throw new IllegalStateException(destination.label() + " is not available yet");
        }
        if (destination == activeDestination) {
            return;
        }

        String resourcePath = screenCatalog.resourceFor(destination);
        URL resource = ScreenNavigator.class.getResource(resourcePath);
        if (resource == null) {
            throw new IOException("Desktop screen FXML was not found: " + resourcePath);
        }

        FXMLLoader loader = new FXMLLoader(resource);
        loader.setControllerFactory(controllerFactory::apply);
        Parent nextRoot = loader.load();
        Object nextController = loader.getController();

        Object previousController = activeController;
        contentHost.getChildren().setAll(nextRoot);
        activeDestination = destination;
        activeController = nextController;
        if (nextRoot instanceof ScrollPane scrollPane) {
            Platform.runLater(() -> {
                if (contentHost.getChildren().contains(nextRoot)) {
                    scrollPane.applyCss();
                    scrollPane.layout();
                    scrollPane.setVvalue(scrollPane.getVmin());
                }
            });
        }
        disposeController(previousController);
    }

    public NavigationDestination activeDestination() {
        return activeDestination;
    }

    public Object activeController() {
        return activeController;
    }

    @Override
    public void close() {
        contentHost.getChildren().clear();
        Object previousController = activeController;
        activeController = null;
        activeDestination = null;
        disposeController(previousController);
    }

    private void disposeController(Object controller) {
        if (controller instanceof DisposableScreenController disposable) {
            disposable.dispose();
        }
    }
}
