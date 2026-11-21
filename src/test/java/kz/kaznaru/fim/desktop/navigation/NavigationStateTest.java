package kz.kaznaru.fim.desktop.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NavigationStateTest {

    @Test
    void dashboardIsTheOnlyAvailableInitialDestination() {
        NavigationState state = new NavigationState();

        assertEquals(NavigationDestination.DASHBOARD, state.activeDestination());
        assertTrue(state.isActive(NavigationDestination.DASHBOARD));
        for (NavigationDestination destination : NavigationDestination.values()) {
            assertEquals(destination == NavigationDestination.DASHBOARD,
                    destination.isAvailable());
        }
    }

    @Test
    void unavailableDestinationsCannotReplaceTheActiveDashboard() {
        NavigationState state = new NavigationState();

        assertThrows(IllegalStateException.class,
                () -> state.activate(NavigationDestination.EVENT_HISTORY));

        assertEquals(NavigationDestination.DASHBOARD, state.activeDestination());
    }
}
