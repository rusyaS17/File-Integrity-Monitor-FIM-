package kz.kaznaru.fim.database;

import kz.kaznaru.fim.model.FileStatus;

public record MonitoredFileRecord(
        long id,
        String monitorRoot,
        String filePath,
        String sha256Hash,
        FileStatus status,
        String details,
        String lastSeenUtc) {
}
