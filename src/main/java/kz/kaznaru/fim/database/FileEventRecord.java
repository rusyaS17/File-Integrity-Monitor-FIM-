package kz.kaznaru.fim.database;

import kz.kaznaru.fim.model.FileStatus;

public record FileEventRecord(
        long id,
        long monitoredFileId,
        String monitorRoot,
        String filePath,
        FileStatus eventType,
        String oldHash,
        String newHash,
        String details,
        String occurredAtUtc) {
}
