package kz.kaznaru.fim.database;

import kz.kaznaru.fim.model.EventActivityBucket;
import kz.kaznaru.fim.model.FileStatus;
import kz.kaznaru.fim.model.FileStatusCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DashboardRepositoryQueryTest {

    private static final String ROOT = "C:\\monitored";

    @TempDir
    Path temporaryDirectory;

    private DatabaseManager databaseManager;
    private FileRepository fileRepository;
    private EventRepository eventRepository;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = new DatabaseManager(temporaryDirectory.resolve("fim.sqlite"));
        databaseManager.initializeSchema();
        fileRepository = new FileRepository(databaseManager);
        eventRepository = new EventRepository(databaseManager);
    }

    @Test
    void returnsZeroCountsForAnEmptyDatabaseAndMissingStatuses() throws Exception {
        FileStatusCounts counts = fileRepository.getStatusCountsByRoot(ROOT);

        assertEquals(0, counts.totalFiles());
        for (FileStatus status : FileStatus.values()) {
            assertEquals(0, counts.count(status));
        }
    }

    @Test
    void dashboardDtosDefensivelyCopyTheirCountMaps() {
        EnumMap<FileStatus, Long> fileCounts = new EnumMap<>(FileStatus.class);
        fileCounts.put(FileStatus.SAFE, 2L);
        FileStatusCounts statusCounts = new FileStatusCounts(fileCounts);
        fileCounts.put(FileStatus.SAFE, 9L);
        assertEquals(2, statusCounts.count(FileStatus.SAFE));
        assertThrows(UnsupportedOperationException.class,
                () -> statusCounts.countsByStatus().put(FileStatus.SAFE, 9L));

        EnumMap<FileStatus, Long> eventCounts = new EnumMap<>(FileStatus.class);
        eventCounts.put(FileStatus.MODIFIED, 1L);
        EventActivityBucket bucket = new EventActivityBucket(Instant.EPOCH, eventCounts);
        eventCounts.put(FileStatus.MODIFIED, 9L);
        assertEquals(1, bucket.count(FileStatus.MODIFIED));
        assertThrows(UnsupportedOperationException.class,
                () -> bucket.eventCountsByType().put(FileStatus.MODIFIED, 9L));
    }

    @Test
    void aggregatesEveryFileStatusAndExcludesDeletedFromTotal() throws Exception {
        for (FileStatus status : FileStatus.values()) {
            saveFile(ROOT, "file-" + status + ".txt", status);
        }

        FileStatusCounts counts = fileRepository.getStatusCountsByRoot(ROOT);

        assertEquals(4, counts.totalFiles());
        for (FileStatus status : FileStatus.values()) {
            assertEquals(1, counts.count(status), "count for " + status);
        }
        assertEquals(0, fileRepository.getStatusCountsByRoot("C:\\other").totalFiles());
    }

    @Test
    void recentEventsAreRootFilteredNewestFirstAndPaginatedWithoutDuplicates()
            throws Exception {
        long fileId = saveFile(ROOT, "history.txt", FileStatus.SAFE);
        Instant base = Instant.parse("2026-11-21T10:00:00Z");
        saveEvent(fileId, ROOT, "history.txt", FileStatus.NEW,
                base, "oldest");
        saveEvent(fileId, ROOT, "history.txt", FileStatus.MODIFIED,
                base.plusSeconds(60), "middle");
        saveEvent(fileId, ROOT, "history.txt", FileStatus.SAFE,
                base.plusSeconds(60), "middle-tie-later-id");
        saveEvent(fileId, ROOT, "history.txt", FileStatus.ERROR,
                base.plusSeconds(120), "newest");
        saveEvent(fileId, ROOT, "history.txt", FileStatus.NEW,
                base.plusSeconds(180).plusNanos(2), "sub-millisecond-newer");
        saveEvent(fileId, ROOT, "history.txt", FileStatus.MODIFIED,
                base.plusSeconds(180).plusNanos(1), "sub-millisecond-older");

        long otherRootFileId = saveFile("C:\\other", "history.txt", FileStatus.SAFE);
        saveEvent(otherRootFileId, "C:\\other", "history.txt", FileStatus.DELETED,
                base.plusSeconds(180), "other-root");

        List<FileEventRecord> firstPage =
                eventRepository.findRecentByRoot(ROOT, 3, 0);
        List<FileEventRecord> secondPage =
                eventRepository.findRecentByRoot(ROOT, 3, 3);
        List<String> details = new ArrayList<>();
        firstPage.forEach(event -> details.add(event.details()));
        secondPage.forEach(event -> details.add(event.details()));

        assertEquals(List.of(
                "sub-millisecond-newer",
                "sub-millisecond-older",
                "newest",
                "middle-tie-later-id",
                "middle",
                "oldest"), details);
        assertEquals(6, details.stream().distinct().count());
        assertTrue(eventRepository.findRecentByRoot(ROOT, 2, 6).isEmpty());
    }

    @Test
    void recentEventsCanBeFilteredByEventTypeAndReturnEmptyResults() throws Exception {
        long fileId = saveFile(ROOT, "filter.txt", FileStatus.SAFE);
        Instant time = Instant.parse("2026-11-21T10:00:00Z");
        saveEvent(fileId, ROOT, "filter.txt", FileStatus.NEW, time, "new");
        saveEvent(fileId, ROOT, "filter.txt", FileStatus.MODIFIED,
                time.plusSeconds(1), "modified");

        List<FileEventRecord> newEvents = eventRepository.findRecentByRoot(
                ROOT, 10, 0, Optional.of(FileStatus.NEW));

        assertEquals(List.of("new"), newEvents.stream()
                .map(FileEventRecord::details)
                .toList());
        assertTrue(eventRepository.findRecentByRoot(
                ROOT, 10, 0, Optional.of(FileStatus.DELETED)).isEmpty());
        assertTrue(eventRepository.findRecentByRoot(
                "C:\\missing", 10, 0).isEmpty());
    }

    @Test
    void groupsOnlyPersistedEventsIntoUtcBucketsWithInclusiveStartAndExclusiveEnd()
            throws Exception {
        long fileId = saveFile(ROOT, "activity.txt", FileStatus.SAFE);
        Instant from = Instant.parse("2026-11-21T10:00:00.000000001Z");
        Instant to = from.plus(Duration.ofHours(2));
        saveEvent(fileId, ROOT, "activity.txt", FileStatus.NEW,
                from, "at-start");
        saveEvent(fileId, ROOT, "activity.txt", FileStatus.ERROR,
                from.plus(Duration.ofHours(1)).minusNanos(2), "before-hour-boundary");
        saveEvent(fileId, ROOT, "activity.txt", FileStatus.MODIFIED,
                from.plus(Duration.ofHours(1)), "at-hour-boundary");
        saveEvent(fileId, ROOT, "activity.txt", FileStatus.DELETED,
                to, "at-exclusive-end");
        saveEvent(fileId, ROOT, "activity.txt", FileStatus.SAFE,
                from.minusNanos(1), "before-range");

        List<EventActivityBucket> buckets = eventRepository.findActivityByRoot(
                ROOT, from, to, Duration.ofHours(1));

        assertEquals(2, buckets.size());
        assertEquals(Instant.parse("2026-11-21T10:00:00Z"), buckets.get(0).bucketStartUtc());
        assertEquals(2, buckets.get(0).totalEvents());
        assertEquals(1, buckets.get(0).count(FileStatus.NEW));
        assertEquals(1, buckets.get(0).count(FileStatus.ERROR));
        assertEquals(Instant.parse("2026-11-21T11:00:00Z"), buckets.get(1).bucketStartUtc());
        assertEquals(1, buckets.get(1).totalEvents());
        assertEquals(1, buckets.get(1).count(FileStatus.MODIFIED));
        assertTrue(eventRepository.findActivityByRoot(
                "C:\\missing", from, to, Duration.ofHours(1)).isEmpty());
    }

    @Test
    void validatesPaginationAndActivityRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> eventRepository.findRecentByRoot(ROOT, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> eventRepository.findRecentByRoot(ROOT, 10, -1));
        assertThrows(IllegalArgumentException.class,
                () -> eventRepository.findActivityByRoot(
                        ROOT, Instant.EPOCH, Instant.EPOCH, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class,
                () -> eventRepository.findActivityByRoot(
                        ROOT, Instant.EPOCH, Instant.EPOCH.plusSeconds(10), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> eventRepository.findActivityByRoot(
                        ROOT,
                        Instant.EPOCH,
                        Instant.EPOCH.plusSeconds(10),
                        Duration.ofMillis(500)));
    }

    private long saveFile(String root, String fileName, FileStatus status) throws SQLException {
        String filePath = root + "\\" + fileName;
        return fileRepository.save(new MonitoredFileRecord(
                0,
                root,
                filePath,
                null,
                status,
                "test record",
                Instant.parse("2026-11-21T09:00:00Z").toString()));
    }

    private void saveEvent(
            long fileId,
            String root,
            String fileName,
            FileStatus eventType,
            Instant occurredAt,
            String details) throws SQLException {
        eventRepository.save(new FileEventRecord(
                0,
                fileId,
                root,
                root + "\\" + fileName,
                eventType,
                null,
                null,
                details,
                occurredAt.toString()));
    }
}
