package kz.kaznaru.fim.application;

import kz.kaznaru.fim.database.DatabaseManager;
import kz.kaznaru.fim.database.EventRepository;
import kz.kaznaru.fim.database.FileRepository;
import kz.kaznaru.fim.model.FileScanResult;
import kz.kaznaru.fim.model.FileStatus;
import kz.kaznaru.fim.model.ScanReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ScanPersistenceNotificationTest {

    @TempDir
    Path temporaryDirectory;

    private DatabaseManager databaseManager;
    private FileRepository fileRepository;
    private EventRepository eventRepository;
    private ScanPersistenceService persistenceService;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        databaseManager = new DatabaseManager(temporaryDirectory.resolve("fim.sqlite"));
        databaseManager.initializeSchema();
        fileRepository = new FileRepository(databaseManager);
        eventRepository = new EventRepository(databaseManager);
        persistenceService = new ScanPersistenceService(databaseManager);
    }

    @Test
    void notifiesOnceAfterCommitAndIncludesRootAndUtcTimestamp() throws Exception {
        AtomicInteger callbackCount = new AtomicInteger();
        AtomicReference<RootUpdatedEvent> observedEvent = new AtomicReference<>();
        AtomicReference<Integer> visibleRowsAtNotification = new AtomicReference<>();
        Consumer<RootUpdatedEvent> listener = event -> {
            callbackCount.incrementAndGet();
            observedEvent.set(event);
            try {
                visibleRowsAtNotification.set(
                        fileRepository.findByRoot(event.monitorRoot()).size());
            } catch (SQLException exception) {
                throw new RuntimeException(exception);
            }
        };
        persistenceService.addRootUpdatedListener(listener);
        persistenceService.addRootUpdatedListener(listener);

        Instant before = Instant.now();
        persistenceService.persist(root, reportFor(root.resolve("new.txt")));
        Instant after = Instant.now();

        assertEquals(1, callbackCount.get());
        assertEquals(root.toAbsolutePath().normalize().toString(),
                observedEvent.get().monitorRoot());
        assertFalse(observedEvent.get().timestampUtc().isBefore(before));
        assertFalse(observedEvent.get().timestampUtc().isAfter(after));
        assertEquals(1, visibleRowsAtNotification.get());
    }

    @Test
    void doesNotNotifyWhenPersistenceTransactionRollsBack() throws Exception {
        databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    CREATE TRIGGER reject_file_event
                    BEFORE INSERT ON file_events
                    BEGIN
                        SELECT RAISE(ABORT, 'forced persistence failure');
                    END
                    """)) {
                statement.execute();
            }
            return null;
        });
        AtomicInteger callbackCount = new AtomicInteger();
        persistenceService.addRootUpdatedListener(event -> callbackCount.incrementAndGet());

        assertThrows(SQLException.class,
                () -> persistenceService.persist(root, reportFor(root.resolve("new.txt"))));

        assertEquals(0, callbackCount.get());
        assertTrue(fileRepository.findByRoot(root.toString()).isEmpty());
        assertTrue(eventRepository.findAll().isEmpty());
    }

    @Test
    void listenerCanBeRemoved() throws Exception {
        AtomicInteger callbackCount = new AtomicInteger();
        Consumer<RootUpdatedEvent> listener = event -> callbackCount.incrementAndGet();
        persistenceService.addRootUpdatedListener(listener);

        assertTrue(persistenceService.removeRootUpdatedListener(listener));
        persistenceService.persist(root, reportFor(root.resolve("new.txt")));

        assertEquals(0, callbackCount.get());
    }

    private ScanReport reportFor(Path path) {
        return new ScanReport(List.of(new FileScanResult(
                path, FileStatus.NEW, "File is not present in the baseline", "test-hash")));
    }
}
