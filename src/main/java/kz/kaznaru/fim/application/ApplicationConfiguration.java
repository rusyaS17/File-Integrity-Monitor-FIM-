package kz.kaznaru.fim.application;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Centralized locations used by the console application. */
public final class ApplicationConfiguration {

    public static final String BASE_DIRECTORY_PROPERTY = "fim.base-dir";
    public static final String BASE_DIRECTORY_ENVIRONMENT = "FIM_BASE_DIR";

    private final Path applicationDirectory;
    private final Path monitoredDirectory;
    private final Path baselineFile;

    private ApplicationConfiguration(Path applicationDirectory) {
        this.applicationDirectory = applicationDirectory.toAbsolutePath().normalize();
        this.monitoredDirectory = this.applicationDirectory.resolve("monitored").normalize();
        this.baselineFile = this.applicationDirectory.resolve("baseline.txt").normalize();
    }

    public static ApplicationConfiguration forDirectory(Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("Application directory must not be null");
        }
        return new ApplicationConfiguration(directory);
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
