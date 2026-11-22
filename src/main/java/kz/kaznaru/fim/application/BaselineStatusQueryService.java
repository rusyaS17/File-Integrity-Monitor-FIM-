package kz.kaznaru.fim.application;

import kz.kaznaru.fim.core.BaselineManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Reads baseline status without loading, repairing, or changing baseline files. */
public final class BaselineStatusQueryService {

    private final ApplicationConfiguration configuration;
    private final BaselineManager baselineManager;

    public BaselineStatusQueryService(
            ApplicationConfiguration configuration,
            BaselineManager baselineManager) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.baselineManager = Objects.requireNonNull(baselineManager, "baselineManager");
    }

    public BaselineStatus query() {
        Path baselineFile = configuration.baselineFile().toAbsolutePath().normalize();
        Path backupFile = baselineFile.resolveSibling(
                baselineFile.getFileName() + BaselineManager.BACKUP_SUFFIX);
        boolean primaryExists = Files.isRegularFile(baselineFile);
        boolean backupExists = Files.isRegularFile(backupFile);
        boolean recoveredFromBackup = baselineManager.wasRecoveredFromBackup();

        BaselineStatus.State state = !primaryExists
                ? BaselineStatus.State.NOT_CREATED
                : recoveredFromBackup
                        ? BaselineStatus.State.RECOVERED
                        : BaselineStatus.State.ACTIVE;

        return new BaselineStatus(
                primaryExists,
                backupExists,
                baselineFile,
                configuration.monitoredDirectory(),
                baselineManager.getBaselineEntries().size(),
                recoveredFromBackup,
                state);
    }
}
