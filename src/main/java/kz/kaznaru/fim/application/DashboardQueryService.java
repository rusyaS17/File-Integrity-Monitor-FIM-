package kz.kaznaru.fim.application;

import kz.kaznaru.fim.database.DatabaseManager;
import kz.kaznaru.fim.database.EventRepository;
import kz.kaznaru.fim.database.FileRepository;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Combines bounded repository queries into an immutable dashboard snapshot. */
public final class DashboardQueryService {

    private final FileRepository fileRepository;
    private final EventRepository eventRepository;

    public DashboardQueryService(DatabaseManager databaseManager) {
        this(new FileRepository(Objects.requireNonNull(databaseManager, "databaseManager")),
                new EventRepository(databaseManager));
    }

    public DashboardQueryService(
            FileRepository fileRepository,
            EventRepository eventRepository) {
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository");
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
    }

    public DashboardSnapshot query(
            Path monitorRoot,
            Instant fromInclusive,
            Instant toExclusive,
            Duration bucketSize,
            int recentEventLimit) throws SQLException {
        Objects.requireNonNull(monitorRoot, "monitorRoot");
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        Objects.requireNonNull(bucketSize, "bucketSize");
        if (recentEventLimit <= 0) {
            throw new IllegalArgumentException("recentEventLimit must be positive");
        }
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
        if (bucketSize.isNegative() || bucketSize.isZero()
                || bucketSize.getNano() != 0 || bucketSize.getSeconds() <= 0) {
            throw new IllegalArgumentException(
                    "bucketSize must be a positive whole number of seconds");
        }

        String root = monitorRoot.toAbsolutePath().normalize().toString();
        var counts = fileRepository.getStatusCountsByRoot(root);
        var recentEvents = eventRepository.findRecentByRoot(
                root, recentEventLimit, 0, Optional.empty());
        var activityBuckets = eventRepository.findActivityByRoot(
                root, fromInclusive, toExclusive, bucketSize);

        return new DashboardSnapshot(
                root,
                fromInclusive,
                toExclusive,
                Instant.now(),
                counts,
                recentEvents,
                activityBuckets);
    }
}
