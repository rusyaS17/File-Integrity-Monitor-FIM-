package kz.kaznaru.fim.desktop.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NavigationStateTest {

    @Test
    void dashboardIsTheInitialDestinationAndScannerScreensAreAvailable() {
        NavigationState state = new NavigationState();

        assertEquals(NavigationDestination.DASHBOARD, state.activeDestination());
        assertTrue(state.isActive(NavigationDestination.DASHBOARD));
        assertTrue(NavigationDestination.DASHBOARD.isAvailable());
        assertTrue(NavigationDestination.FILE_SCANNER.isAvailable());
        assertTrue(NavigationDestination.BASELINE_MANAGEMENT.isAvailable());
        assertFalse(NavigationDestination.FILE_MONITORING.isAvailable());
        assertFalse(NavigationDestination.EVENT_HISTORY.isAvailable());
        assertFalse(NavigationDestination.SETTINGS.isAvailable());
    }

    @Test
    void availableDestinationsCanBeSelectedAndUnavailableCannotReplaceThem() {
        NavigationState state = new NavigationState();
        state.activate(NavigationDestination.FILE_SCANNER);
        assertEquals(NavigationDestination.FILE_SCANNER, state.activeDestination());
        state.activate(NavigationDestination.BASELINE_MANAGEMENT);
        assertEquals(NavigationDestination.BASELINE_MANAGEMENT, state.activeDestination());

        assertThrows(IllegalStateException.class,
                () -> state.activate(NavigationDestination.EVENT_HISTORY));

        assertEquals(NavigationDestination.BASELINE_MANAGEMENT, state.activeDestination());
    }
}
