package kz.kaznaru.fim.application;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable, read-only summary of the configured baseline state. */
public record BaselineStatus(
        boolean primaryFileExists,
        boolean backupFileExists,
        Path baselineFile,
        Path monitoredDirectory,
        int entryCount,
        boolean recoveredFromBackup,
        State state) {

    public BaselineStatus {
        baselineFile = Objects.requireNonNull(baselineFile, "baselineFile")
                .toAbsolutePath()
                .normalize();
        monitoredDirectory = Objects.requireNonNull(
                monitoredDirectory, "monitoredDirectory")
                .toAbsolutePath()
                .normalize();
        state = Objects.requireNonNull(state, "state");
        if (entryCount < 0) {
            throw new IllegalArgumentException("Baseline entry count must not be negative");
        }
    }

    public enum State {
        NOT_CREATED,
        ACTIVE,
        RECOVERED
    }
}
