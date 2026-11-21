package kz.kaznaru.fim.application;

import kz.kaznaru.fim.database.DatabaseManager;
import kz.kaznaru.fim.database.EventRepository;
import kz.kaznaru.fim.database.FileEventRecord;
import kz.kaznaru.fim.database.FileRepository;
import kz.kaznaru.fim.database.MonitoredFileRecord;
import kz.kaznaru.fim.model.EventActivityBucket;
import kz.kaznaru.fim.model.FileStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DashboardQueryServiceTest {

    @TempDir
    Path temporaryDirectory;

    private DatabaseManager databaseManager;
    private FileRepository fileRepository;
    private EventRepository eventRepository;
    private DashboardQueryService queryService;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        databaseManager = new DatabaseManager(temporaryDirectory.resolve("fim.sqlite"));
        databaseManager.initializeSchema();
        fileRepository = new FileRepository(databaseManager);
        eventRepository = new EventRepository(databaseManager);
        queryService = new DashboardQueryService(fileRepository, eventRepository);
    }

    @Test
    void snapshotCombinesCountsRecentEventsAndActivityWithinRequestedRange()
            throws Exception {
        Map<FileStatus, Long> fileIds = new EnumMap<>(FileStatus.class);
        for (FileStatus status : FileStatus.values()) {
            String path = root.resolve(status.name() + ".txt").toString();
            fileIds.put(status, fileRepository.save(new MonitoredFileRecord(
                    0, root.toString(), path, null, status, status.name(),
                    Instant.parse("2026-11-21T09:00:00Z").toString())));
        }
        Instant from = Instant.parse("2026-11-21T10:00:00Z");
        saveEvent(fileIds.get(FileStatus.NEW), FileStatus.NEW, from, "first");
        saveEvent(fileIds.get(FileStatus.MODIFIED), FileStatus.MODIFIED,
                from.plusSeconds(60), "second");
        saveEvent(fileIds.get(FileStatus.DELETED), FileStatus.DELETED,
                from.plusSeconds(120), "latest-deleted");

        DashboardSnapshot snapshot = queryService.query(
                root,
                from,
                from.plusSeconds(180),
                Duration.ofMinutes(1),
                1);

        assertEquals(root.toAbsolutePath().normalize().toString(), snapshot.monitorRoot());
        assertEquals(from, snapshot.fromInclusive());
        assertEquals(from.plusSeconds(180), snapshot.toExclusive());
        assertEquals(4, snapshot.fileStatusCounts().totalFiles());
        for (FileStatus status : FileStatus.values()) {
            assertEquals(1, snapshot.fileStatusCounts().count(status));
        }
        assertEquals(1, snapshot.recentEvents().size());
        assertEquals("latest-deleted", snapshot.recentEvents().get(0).details());
        assertEquals(3, snapshot.activityBuckets().size());
        assertEquals(1, snapshot.activityBuckets().get(0).count(FileStatus.NEW));
        assertEquals(1, snapshot.activityBuckets().get(1).count(FileStatus.MODIFIED));
        assertEquals(1, snapshot.activityBuckets().get(2).count(FileStatus.DELETED));
    }

    @Test
    void emptyDatabaseReturnsZeroCountsAndEmptyLists() throws Exception {
        DashboardSnapshot snapshot = queryService.query(
                root,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(3600),
                Duration.ofMinutes(5),
                20);

        assertEquals(0, snapshot.fileStatusCounts().totalFiles());
        for (FileStatus status : FileStatus.values()) {
            assertEquals(0, snapshot.fileStatusCounts().count(status));
        }
        assertTrue(snapshot.recentEvents().isEmpty());
        assertTrue(snapshot.activityBuckets().isEmpty());
    }

    @Test
    void snapshotCopiesItsEventAndBucketLists() throws Exception {
        List<FileEventRecord> events = new java.util.ArrayList<>();
        List<EventActivityBucket> buckets = new java.util.ArrayList<>();
        DashboardSnapshot snapshot = new DashboardSnapshot(
                root.toString(),
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                Instant.now(),
                fileRepository.getStatusCountsByRoot(root.toString()),
                events,
                buckets);
        events.clear();
        buckets.clear();

        assertTrue(snapshot.recentEvents().isEmpty());
        assertTrue(snapshot.activityBuckets().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.recentEvents().add(null));
    }

    private void saveEvent(
            long fileId, FileStatus status, Instant occurredAt, String details)
            throws Exception {
        eventRepository.save(new FileEventRecord(
                0,
                fileId,
                root.toString(),
                root.resolve(details + ".txt").toString(),
                status,
                null,
                null,
                details,
                occurredAt.toString()));
    }
}
