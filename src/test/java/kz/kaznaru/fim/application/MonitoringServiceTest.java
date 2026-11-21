package kz.kaznaru.fim.application;

import kz.kaznaru.fim.core.BaselineManager;
import kz.kaznaru.fim.core.DirectoryScanner;
import kz.kaznaru.fim.core.FileWatchEvent;
import kz.kaznaru.fim.core.HashCalculator;
import kz.kaznaru.fim.database.DatabaseManager;
import kz.kaznaru.fim.database.EventRepository;
import kz.kaznaru.fim.database.FileEventRecord;
import kz.kaznaru.fim.model.FileStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MonitoringServiceTest {

    @TempDir
    Path temporaryDirectory;

    private Path root;
    private DatabaseManager databaseManager;
    private ScanPersistenceService persistenceService;
    private EventRepository eventRepository;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        databaseManager = new DatabaseManager(temporaryDirectory.resolve("fim.sqlite"));
        databaseManager.initializeSchema();
        persistenceService = new ScanPersistenceService(databaseManager);
        eventRepository = new EventRepository(databaseManager);
    }

    @Test
    void monitorsCreateModifyDeleteAndNeverChangesBaseline() throws Exception {
        Path trackedFile = root.resolve("tracked.txt");
        Files.writeString(trackedFile, "trusted");
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");

        BaselineManager baselineManager = new BaselineManager();
        baselineManager.createBaseline(root);
        baselineManager.saveBaselineToFile(baselineFile);
        byte[] baselineBefore = Files.readAllBytes(baselineFile);
        EventProcessor processor = eventProcessor(baselineManager.getBaselineEntries());
        processor.reconcile();

        try (MonitoringService service = service(processor, Duration.ofMillis(80),
                Duration.ofSeconds(5))) {
            service.start();

            Files.writeString(trackedFile, "modified");
            awaitEvent(trackedFile, FileStatus.MODIFIED);

            Path createdFile = root.resolve("created.txt");
            Files.writeString(createdFile, "new");
            awaitEvent(createdFile, FileStatus.NEW);

            Files.delete(createdFile);
            awaitEvent(createdFile, FileStatus.DELETED);

            assertTrue(service.isRunning());
            assertNull(service.lastFailure());
        }

        assertArrayEquals(baselineBefore, Files.readAllBytes(baselineFile));
    }

    @Test
    void recursivelyRegistersDirectoriesCreatedWhileMonitoring() throws Exception {
        EventProcessor processor = eventProcessor(Map.of());
        processor.reconcile();

        try (MonitoringService service = service(processor, Duration.ofMillis(60),
                Duration.ofSeconds(5))) {
            service.start();

            Path nestedDirectory = Files.createDirectory(root.resolve("new-directory"));
            awaitCondition(() -> service.registeredDirectoryCount() == 2,
                    Duration.ofSeconds(3));
            Path nestedFile = nestedDirectory.resolve("nested.txt");
            Files.writeString(nestedFile, "first version");
            FileEventRecord created = awaitEvent(nestedFile, FileStatus.NEW);

            Files.writeString(nestedFile, "second version");
            awaitCondition(() -> eventRepository.findByPath(root.toString(), key(nestedFile))
                            .stream()
                            .anyMatch(event -> !event.newHash().equals(created.newHash())),
                    Duration.ofSeconds(3));
        }
    }

    @Test
    void startIsIdempotentAndOnlyOneServiceCanMonitorARoot() throws Exception {
        EventProcessor processor = eventProcessor(Map.of());
        MonitoringService first = service(processor, Duration.ZERO, Duration.ofSeconds(5));
        MonitoringService duplicate = service(processor, Duration.ZERO, Duration.ofSeconds(5));

        try (first; duplicate) {
            first.start();
            first.start();
            assertTrue(first.isRunning());
            assertEquals(1, first.registeredDirectoryCount());
            assertThrows(IllegalStateException.class, duplicate::start);

            first.stop();
            assertFalse(first.isRunning());
            assertEquals(0, first.registeredDirectoryCount());

            duplicate.start();
            assertTrue(duplicate.isRunning());
        }
    }

    @Test
    void overflowTriggersFullReconciliationAndPersistsTheChangedFile() throws Exception {
        Path file = root.resolve("tracked.txt");
        Files.writeString(file, "trusted");
        Map<String, String> baseline = Map.of(key(file), hash(file));
        EventProcessor processor = eventProcessor(baseline);
        processor.reconcile();
        Files.writeString(file, "changed without a watcher notification");

        processor.process(new FileWatchEvent(root, FileWatchEvent.Kind.OVERFLOW,
                "synthetic overflow for test"));

        awaitEvent(file, FileStatus.MODIFIED);
    }

    @Test
    void periodicReconciliationRunsAndDetectsMissedChanges() throws Exception {
        Path file = root.resolve("tracked.txt");
        Files.writeString(file, "trusted");
        Map<String, String> baseline = Map.of(key(file), hash(file));

        CountDownLatch reconciled = new CountDownLatch(1);
        AtomicInteger reconcileCount = new AtomicInteger();
        EventProcessor countingProcessor = new EventProcessor(
                root,
                baseline,
                new DirectoryScanner(),
                persistenceService) {
            @Override
            public void reconcile() throws IOException, SQLException {
                reconcileCount.incrementAndGet();
                super.reconcile();
                reconciled.countDown();
            }
        };
        Files.writeString(file, "changed before watcher registration");
        try (MonitoringService service = service(
                countingProcessor, Duration.ofMillis(50), Duration.ofMillis(100))) {
            service.start();
            assertTrue(reconciled.await(3, TimeUnit.SECONDS));
            awaitEvent(file, FileStatus.MODIFIED);
            assertTrue(reconcileCount.get() >= 1);
        }
    }

    @Test
    void watcherErrorsArePersistedAsErrorEvents() throws Exception {
        EventProcessor processor = eventProcessor(Map.of());
        processor.process(new FileWatchEvent(root.resolve("unreadable"),
                FileWatchEvent.Kind.ERROR, "watch registration failed"));

        awaitEvent(root.resolve("unreadable"), FileStatus.ERROR);
    }

    private EventProcessor eventProcessor(Map<String, String> baseline) {
        return new EventProcessor(root, baseline, new DirectoryScanner(), persistenceService);
    }

    private MonitoringService service(
            EventProcessor processor, Duration debounce, Duration reconciliation) {
        return new MonitoringService(root, processor, debounce, reconciliation,
                failure -> fail("Unexpected monitoring failure: " + failure));
    }

    private FileEventRecord awaitEvent(Path path, FileStatus status) throws Exception {
        FileEventRecord[] found = new FileEventRecord[1];
        awaitCondition(() -> {
            found[0] = eventRepository.findByPath(root.toString(), key(path)).stream()
                    .filter(event -> event.eventType() == status)
                    .findFirst()
                    .orElse(null);
            return found[0] != null;
        }, Duration.ofSeconds(3));
        return found[0];
    }

    private void awaitCondition(CheckedBooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.yield();
        }
        assertTrue(condition.getAsBoolean(), "Condition was not met before timeout");
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private String hash(Path path) throws Exception {
        return new HashCalculator().calculateSHA256(path);
    }

    private String key(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
