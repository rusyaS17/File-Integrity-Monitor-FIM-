package kz.kaznaru.fim.application;

import kz.kaznaru.fim.core.DirectoryScanner;
import kz.kaznaru.fim.core.FileWatchEvent;
import kz.kaznaru.fim.model.FileScanResult;
import kz.kaznaru.fim.model.FileStatus;
import kz.kaznaru.fim.model.ScanReport;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reconciles watcher notifications against the immutable trusted baseline. */
public class EventProcessor {

    private final Path monitoredRoot;
    private final Map<String, String> baseline;
    private final DirectoryScanner directoryScanner;
    private final ScanPersistenceService persistenceService;

    public EventProcessor(
            Path monitoredRoot,
            Map<String, String> baseline,
            DirectoryScanner directoryScanner,
            ScanPersistenceService persistenceService) {
        this.monitoredRoot = Objects.requireNonNull(monitoredRoot, "monitoredRoot")
                .toAbsolutePath().normalize();
        this.baseline = Map.copyOf(Objects.requireNonNull(baseline, "baseline"));
        this.directoryScanner = Objects.requireNonNull(directoryScanner, "directoryScanner");
        this.persistenceService = Objects.requireNonNull(
                persistenceService, "persistenceService");
    }

    public void process(FileWatchEvent event) throws IOException, SQLException {
        Objects.requireNonNull(event, "event");
        if (event.kind() == FileWatchEvent.Kind.ERROR) {
            ScanReport report = directoryScanner.scan(monitoredRoot, baseline);
            List<FileScanResult> results = new ArrayList<>(report.results());
            results.add(new FileScanResult(event.path(),
                    FileStatus.ERROR, event.details()));
            persistenceService.persist(monitoredRoot, new ScanReport(List.copyOf(results)));
            return;
        }
        reconcile();
    }

    public void reconcile() throws IOException, SQLException {
        ScanReport report = directoryScanner.scan(monitoredRoot, baseline);
        persistenceService.persist(monitoredRoot, report);
    }

    public Path monitoredRoot() {
        return monitoredRoot;
    }
}
