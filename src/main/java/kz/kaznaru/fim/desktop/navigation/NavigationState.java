package kz.kaznaru.fim.desktop.navigation;

import java.util.Objects;

/** Tracks the active shell destination independently of JavaFX controls. */
public final class NavigationState {

    private NavigationDestination activeDestination = NavigationDestination.DASHBOARD;

    public NavigationDestination activeDestination() {
        return activeDestination;
    }

    public boolean isActive(NavigationDestination destination) {
        return activeDestination == Objects.requireNonNull(destination, "destination");
    }

    public void activate(NavigationDestination destination) {
        Objects.requireNonNull(destination, "destination");
        if (!destination.isAvailable()) {
            throw new IllegalStateException(
                    destination.label() + " is not available yet");
        }
        activeDestination = destination;
    }
}
