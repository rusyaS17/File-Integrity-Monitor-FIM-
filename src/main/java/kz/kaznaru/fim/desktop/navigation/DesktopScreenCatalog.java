package kz.kaznaru.fim.desktop.navigation;

import java.util.Map;

/** Maps available navigation destinations to their FXML resources. */
public final class DesktopScreenCatalog {

    private static final Map<NavigationDestination, String> SCREEN_RESOURCES = Map.of(
            NavigationDestination.DASHBOARD,
            "/kz/kaznaru/fim/desktop/view/dashboard.fxml",
            NavigationDestination.FILE_SCANNER,
            "/kz/kaznaru/fim/desktop/view/file-scanner.fxml",
            NavigationDestination.BASELINE_MANAGEMENT,
            "/kz/kaznaru/fim/desktop/view/baseline-management.fxml");

    public String resourceFor(NavigationDestination destination) {
        String resource = SCREEN_RESOURCES.get(destination);
        if (resource == null) {
            throw new IllegalArgumentException(
                    "No desktop screen is registered for " + destination);
        }
        return resource;
    }
}
