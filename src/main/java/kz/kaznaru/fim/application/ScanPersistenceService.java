package kz.kaznaru.fim.application;

import kz.kaznaru.fim.database.DatabaseManager;
import kz.kaznaru.fim.database.EventRepository;
import kz.kaznaru.fim.database.FileEventRecord;
import kz.kaznaru.fim.database.FileRepository;
import kz.kaznaru.fim.database.MonitoredFileRecord;
import kz.kaznaru.fim.model.FileScanResult;
import kz.kaznaru.fim.model.FileStatus;
import kz.kaznaru.fim.model.ScanReport;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Persists each scan's latest file state and only emits events for state changes. */
public final class ScanPersistenceService {

    private final DatabaseManager databaseManager;
    private final FileRepository fileRepository;
    private final EventRepository eventRepository;
    private final CopyOnWriteArrayList<Consumer<RootUpdatedEvent>> rootUpdatedListeners =
            new CopyOnWriteArrayList<>();

    public ScanPersistenceService(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager");
        this.fileRepository = new FileRepository(databaseManager);
        this.eventRepository = new EventRepository(databaseManager);
    }

    public void addRootUpdatedListener(Consumer<RootUpdatedEvent> listener) {
        rootUpdatedListeners.addIfAbsent(
                Objects.requireNonNull(listener, "listener"));
    }

    public boolean removeRootUpdatedListener(Consumer<RootUpdatedEvent> listener) {
        return rootUpdatedListeners.remove(Objects.requireNonNull(listener, "listener"));
    }

    public void persist(Path monitoredRoot, ScanReport report) throws SQLException {
        String root = monitoredRoot.toAbsolutePath().normalize().toString();
        databaseManager.inTransaction(connection -> {
            List<MonitoredFileRecord> previousRecords =
                    fileRepository.findByRoot(connection, root);
            Map<String, MonitoredFileRecord> previousByPath = new HashMap<>();
            for (MonitoredFileRecord previous : previousRecords) {
                previousByPath.put(previous.filePath(), previous);
            }

            List<FileScanResult> observations = new ArrayList<>(report.results());
            Set<String> reportedPaths = new HashSet<>();
            for (FileScanResult result : report.results()) {
                reportedPaths.add(normalize(result.path()));
            }
            for (MonitoredFileRecord previous : previousRecords) {
                if (!reportedPaths.contains(previous.filePath())
                        && previous.status() != FileStatus.DELETED) {
                    observations.add(new FileScanResult(
                            Path.of(previous.filePath()),
                            FileStatus.DELETED,
                            "File from a previous scan was not found"));
                }
            }

            for (FileScanResult observation : observations) {
                String path = normalize(observation.path());
                MonitoredFileRecord previous = previousByPath.get(path);
                String now = Instant.now().toString();
                MonitoredFileRecord current = new MonitoredFileRecord(
                        0,
                        root,
                        path,
                        observation.sha256Hash(),
                        observation.status(),
                        observation.details(),
                        now);
                long fileId = fileRepository.save(connection, current);

                if (isChanged(previous, observation)) {
                    eventRepository.save(connection, new FileEventRecord(
                            0,
                            fileId,
                            root,
                            path,
                            observation.status(),
                            previous == null
                                    ? observation.baselineSha256Hash()
                                    : previous.sha256Hash(),
                            observation.sha256Hash(),
                            observation.details(),
                            now));
                }
            }
            return null;
        });
        publishRootUpdated(new RootUpdatedEvent(root, Instant.now()));
    }

    private void publishRootUpdated(RootUpdatedEvent event) {
        for (Consumer<RootUpdatedEvent> listener : rootUpdatedListeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException exception) {
                System.err.println("Root-updated listener failed for "
                        + event.monitorRoot() + ": " + exception.getMessage());
            }
        }
    }

    private boolean isChanged(MonitoredFileRecord previous, FileScanResult current) {
        return previous == null
                || previous.status() != current.status()
                || !Objects.equals(previous.details(), current.details())
                || (current.sha256Hash() != null
                    && !Objects.equals(previous.sha256Hash(), current.sha256Hash()));
    }

    private String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
