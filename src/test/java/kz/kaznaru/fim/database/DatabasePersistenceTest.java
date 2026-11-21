package kz.kaznaru.fim.database;

import kz.kaznaru.fim.application.ScanPersistenceService;
import kz.kaznaru.fim.core.DirectoryScanner;
import kz.kaznaru.fim.core.HashCalculator;
import kz.kaznaru.fim.model.FileScanResult;
import kz.kaznaru.fim.model.FileStatus;
import kz.kaznaru.fim.model.ScanReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DatabasePersistenceTest {

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
    void initializesTablesIndexesAndForeignKeyIdempotently() throws Exception {
        databaseManager.initializeSchema();

        Set<String> schemaObjects = databaseManager.withConnection(connection -> {
            String sql = "SELECT name FROM sqlite_master WHERE type IN ('table', 'index')";
            Set<String> names = new java.util.HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    names.add(resultSet.getString(1));
                }
            }
            return names;
        });

        assertTrue(schemaObjects.contains("monitored_files"));
        assertTrue(schemaObjects.contains("file_events"));
        assertTrue(schemaObjects.contains("idx_monitored_files_root_status"));
        assertTrue(schemaObjects.contains("idx_file_events_root_time"));
        assertTrue(schemaObjects.contains("idx_file_events_path_time"));

        List<String> foreignKeyTargets = databaseManager.withConnection(connection -> {
            List<String> targets = new ArrayList<>();
            try (PreparedStatement statement =
                         connection.prepareStatement("PRAGMA foreign_key_list(file_events)");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    targets.add(resultSet.getString("table"));
                }
            }
            return targets;
        });
        assertTrue(foreignKeyTargets.contains("monitored_files"));
    }

    @Test
    void fileRepositorySupportsCreateReadUpdateAndDelete() throws Exception {
        MonitoredFileRecord initial = new MonitoredFileRecord(
                0,
                "C:\\monitor",
                "C:\\monitor\\a.txt",
                "a".repeat(64),
                FileStatus.SAFE,
                "original",
                Instant.now().toString());

        long id = fileRepository.save(initial);
        Optional<MonitoredFileRecord> inserted =
                fileRepository.findByPath(initial.monitorRoot(), initial.filePath());
        assertTrue(inserted.isPresent());
        assertEquals(id, inserted.orElseThrow().id());
        assertEquals(FileStatus.SAFE, inserted.orElseThrow().status());

        MonitoredFileRecord updated = new MonitoredFileRecord(
                id,
                initial.monitorRoot(),
                initial.filePath(),
                "b".repeat(64),
                FileStatus.MODIFIED,
                "changed",
                Instant.now().toString());
        fileRepository.save(updated);

        MonitoredFileRecord loaded =
                fileRepository.findByPath(initial.monitorRoot(), initial.filePath())
                        .orElseThrow();
        assertEquals(FileStatus.MODIFIED, loaded.status());
        assertEquals("b".repeat(64), loaded.sha256Hash());
        assertEquals(1, fileRepository.findByRoot(initial.monitorRoot()).size());

        assertTrue(fileRepository.deleteByPath(initial.monitorRoot(), initial.filePath()));
        assertFalse(fileRepository.findByPath(initial.monitorRoot(), initial.filePath()).isPresent());
        assertFalse(fileRepository.deleteByPath(initial.monitorRoot(), initial.filePath()));
    }

    @Test
    void rollsBackAllChangesWhenTransactionFails() throws Exception {
        MonitoredFileRecord file = new MonitoredFileRecord(
                0,
                "root",
                "root\\transaction.txt",
                "c".repeat(64),
                FileStatus.SAFE,
                "transaction test",
                Instant.now().toString());

        assertThrows(SQLException.class, () -> databaseManager.inTransaction(connection -> {
            fileRepository.save(connection, file);
            throw new SQLException("force transaction rollback");
        }));

        assertTrue(fileRepository.findByPath(file.monitorRoot(), file.filePath()).isEmpty());
    }

    @Test
    void eventForeignKeyRejectsMissingMonitoredFile() throws Exception {
        FileEventRecord event = new FileEventRecord(
                0,
                Long.MAX_VALUE,
                "root",
                "root\\missing.txt",
                FileStatus.NEW,
                null,
                "d".repeat(64),
                "missing file row",
                Instant.now().toString());

        assertThrows(SQLException.class, () -> eventRepository.save(event));
        assertTrue(eventRepository.findAll().isEmpty());
    }

    @Test
    void persistsAllScanStatusesOnceAndDeduplicatesRepeatedScans() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Path safe = root.resolve("safe.txt");
        Path modified = root.resolve("modified.txt");
        Path deleted = root.resolve("deleted.txt");
        Path newFile = root.resolve("new.txt");
        Path error = root.resolve("error.txt");
        Files.writeString(safe, "safe");
        Files.writeString(modified, "before");
        Files.writeString(deleted, "deleted");

        Map<String, String> baseline = Map.of(
                key(safe), hash(safe),
                key(modified), hash(modified),
                key(deleted), hash(deleted));
        Files.writeString(modified, "after");
        Files.delete(deleted);
        Files.writeString(newFile, "new");

        ScanReport scanned = new DirectoryScanner().scan(root, baseline);
        List<FileScanResult> observations = new ArrayList<>(scanned.results());
        observations.add(new FileScanResult(error, FileStatus.ERROR,
                "injected I/O error"));
        ScanReport report = new ScanReport(List.copyOf(observations));

        ScanPersistenceService persistence = new ScanPersistenceService(databaseManager);
        persistence.persist(root, report);
        persistence.persist(root, report);

        List<FileEventRecord> events = eventRepository.findAll();
        assertEquals(5, events.size());
        assertEquals(EnumSet.allOf(FileStatus.class),
                events.stream().map(FileEventRecord::eventType)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(FileStatus.class))));
        assertTrue(events.stream().allMatch(event -> event.occurredAtUtc().endsWith("Z")));
        assertEquals(1, eventRepository.findByPath(root.toString(), key(modified)).size());
        assertEquals(baseline.get(key(modified)),
                eventRepository.findByPath(root.toString(), key(modified)).get(0).oldHash());
        assertEquals(baseline.get(key(deleted)),
                eventRepository.findByPath(root.toString(), key(deleted)).get(0).oldHash());

        Files.writeString(modified, "modified again");
        ScanReport changedReport = new DirectoryScanner().scan(root, baseline);
        List<FileScanResult> changedObservations = new ArrayList<>(changedReport.results());
        changedObservations.add(new FileScanResult(error, FileStatus.ERROR,
                "injected I/O error"));
        persistence.persist(root, new ScanReport(List.copyOf(changedObservations)));

        assertEquals(6, eventRepository.findAll().size());
        assertEquals(2, eventRepository.findByPath(root.toString(), key(modified)).size());
    }

    @Test
    void recordsDeletionOfPreviouslySeenNewFile() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Path newFile = root.resolve("transient.txt");
        Files.writeString(newFile, "temporary");

        ScanPersistenceService persistence = new ScanPersistenceService(databaseManager);
        String hash = hash(newFile);
        ScanReport created = new ScanReport(List.of(new FileScanResult(
                newFile, FileStatus.NEW, "File is not present in the baseline", hash)));
        persistence.persist(root, created);

        Files.delete(newFile);
        persistence.persist(root, new ScanReport(List.of()));

        List<FileEventRecord> events =
                eventRepository.findByPath(root.toString(), key(newFile));
        assertEquals(List.of(FileStatus.NEW, FileStatus.DELETED),
                events.stream().map(FileEventRecord::eventType).toList());
        assertEquals(FileStatus.DELETED,
                fileRepository.findByPath(root.toString(), key(newFile))
                        .orElseThrow().status());
    }

    private String hash(Path path) throws Exception {
        return new HashCalculator().calculateSHA256(path);
    }

    private String key(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
