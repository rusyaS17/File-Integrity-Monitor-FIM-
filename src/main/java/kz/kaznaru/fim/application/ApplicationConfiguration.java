package kz.kaznaru.fim.application;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/** Centralized locations used by the console application. */
public final class ApplicationConfiguration {

    public static final String BASE_DIRECTORY_PROPERTY = "fim.base-dir";
    public static final String BASE_DIRECTORY_ENVIRONMENT = "FIM_BASE_DIR";
    public static final String WATCH_DEBOUNCE_PROPERTY = "fim.watch-debounce-ms";
    public static final String WATCH_DEBOUNCE_ENVIRONMENT = "FIM_WATCH_DEBOUNCE_MS";
    public static final String RECONCILIATION_INTERVAL_PROPERTY =
            "fim.reconciliation-interval-ms";
    public static final String RECONCILIATION_INTERVAL_ENVIRONMENT =
            "FIM_RECONCILIATION_INTERVAL_MS";
    public static final Duration DEFAULT_WATCH_DEBOUNCE = Duration.ofMillis(250);
    public static final Duration DEFAULT_RECONCILIATION_INTERVAL = Duration.ofSeconds(30);

    private final Path applicationDirectory;
    private final Path monitoredDirectory;
    private final Path baselineFile;
    private final Path databaseFile;
    private final Duration watchDebounceDuration;
    private final Duration reconciliationInterval;

    private ApplicationConfiguration(
            Path applicationDirectory,
            Duration watchDebounceDuration,
            Duration reconciliationInterval) {
        this.applicationDirectory = applicationDirectory.toAbsolutePath().normalize();
        this.monitoredDirectory = this.applicationDirectory.resolve("monitored").normalize();
        this.baselineFile = this.applicationDirectory.resolve("baseline.txt").normalize();
        this.databaseFile = this.applicationDirectory.resolve("fim.db").normalize();
        this.watchDebounceDuration = watchDebounceDuration;
        this.reconciliationInterval = reconciliationInterval;
    }

    public static ApplicationConfiguration forDirectory(Path directory) {
        return forDirectory(directory,
                configuredDuration(WATCH_DEBOUNCE_PROPERTY, WATCH_DEBOUNCE_ENVIRONMENT,
                        DEFAULT_WATCH_DEBOUNCE),
                configuredDuration(RECONCILIATION_INTERVAL_PROPERTY,
                        RECONCILIATION_INTERVAL_ENVIRONMENT,
                        DEFAULT_RECONCILIATION_INTERVAL));
    }

    public static ApplicationConfiguration forDirectory(
            Path directory,
            Duration watchDebounceDuration,
            Duration reconciliationInterval) {
        if (directory == null) {
            throw new IllegalArgumentException("Application directory must not be null");
        }
        if (watchDebounceDuration == null || watchDebounceDuration.isNegative()) {
            throw new IllegalArgumentException("Watch debounce duration must not be negative");
        }
        if (reconciliationInterval == null
                || reconciliationInterval.isZero()
                || reconciliationInterval.isNegative()) {
            throw new IllegalArgumentException("Reconciliation interval must be positive");
        }
        return new ApplicationConfiguration(
                directory, watchDebounceDuration, reconciliationInterval);
    }

    public static ApplicationConfiguration defaults() {
        String propertyDirectory = System.getProperty(BASE_DIRECTORY_PROPERTY);
        if (propertyDirectory != null && !propertyDirectory.isBlank()) {
            return forDirectory(Path.of(propertyDirectory).toAbsolutePath());
        }

        String environmentDirectory = System.getenv(BASE_DIRECTORY_ENVIRONMENT);
        if (environmentDirectory != null && !environmentDirectory.isBlank()) {
            return forDirectory(Path.of(environmentDirectory).toAbsolutePath());
        }

        Path currentDirectory = Path.of("").toAbsolutePath().normalize();
        Path projectDirectory = findProjectDirectory(currentDirectory);
        if (projectDirectory != null) {
            return forDirectory(projectDirectory);
        }

        projectDirectory = findProjectDirectoryFromCodeSource();
        if (projectDirectory != null) {
            return forDirectory(projectDirectory);
        }

        return forDirectory(currentDirectory);
    }

    public Path applicationDirectory() {
        return applicationDirectory;
    }

    public Path monitoredDirectory() {
        return monitoredDirectory;
    }

    public Path baselineFile() {
        return baselineFile;
    }

    public Path databaseFile() {
        return databaseFile;
    }

    public Duration watchDebounceDuration() {
        return watchDebounceDuration;
    }

    public Duration reconciliationInterval() {
        return reconciliationInterval;
    }

    private static Duration configuredDuration(
            String propertyName, String environmentName, Duration defaultValue) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentName);
        }
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long milliseconds = Long.parseLong(value);
            return Duration.ofMillis(milliseconds);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Invalid millisecond value for " + propertyName + ": " + value,
                    exception);
        }
    }

    private static Path findProjectDirectory(Path start) {
        Path candidate = start.toAbsolutePath().normalize();
        for (int depth = 0; candidate != null && depth < 10; depth++) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    private static Path findProjectDirectoryFromCodeSource() {
        try {
            Path codeLocation = Paths.get(ApplicationConfiguration.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
                    .toAbsolutePath()
                    .normalize();

            Path candidate = Files.isRegularFile(codeLocation) ? codeLocation.getParent() : codeLocation;
            for (int depth = 0; candidate != null && depth < 10; depth++) {
                Path projectDirectory = findProjectDirectory(candidate);
                if (projectDirectory != null) {
                    return projectDirectory;
                }
                candidate = candidate.getParent();
            }
            return null;
        } catch (URISyntaxException | SecurityException exception) {
            return null;
        }
    }
}
