package kz.kaznaru.fim.desktop.navigation;

/** Destinations shown in the desktop shell navigation. */
public enum NavigationDestination {
    DASHBOARD("Dashboard", true),
    FILE_MONITORING("File Monitoring", false),
    FILE_SCANNER("File Scanner", false),
    EVENT_HISTORY("Event History", false),
    BASELINE_MANAGEMENT("Baseline Management", false),
    SETTINGS("Settings", false);

    private final String label;
    private final boolean available;

    NavigationDestination(String label, boolean available) {
        this.label = label;
        this.available = available;
    }

    public String label() {
        return label;
    }

    public boolean isAvailable() {
        return available;
    }
}
