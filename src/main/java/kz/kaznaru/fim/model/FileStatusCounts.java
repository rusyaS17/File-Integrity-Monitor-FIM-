package kz.kaznaru.fim.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable counts of the latest observed state for each file status. */
public record FileStatusCounts(Map<FileStatus, Long> countsByStatus) {

    public FileStatusCounts {
        Objects.requireNonNull(countsByStatus, "countsByStatus");
        EnumMap<FileStatus, Long> normalizedCounts = new EnumMap<>(FileStatus.class);
        for (Map.Entry<FileStatus, Long> entry : countsByStatus.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException("File status counts must be non-negative");
            }
        }
        for (FileStatus status : FileStatus.values()) {
            normalizedCounts.put(status, countsByStatus.getOrDefault(status, 0L));
        }
        countsByStatus = Collections.unmodifiableMap(normalizedCounts);
    }

    public long count(FileStatus status) {
        return countsByStatus.get(Objects.requireNonNull(status, "status"));
    }

    public long totalFiles() {
        return count(FileStatus.SAFE)
                + count(FileStatus.MODIFIED)
                + count(FileStatus.NEW)
                + count(FileStatus.ERROR);
    }
}
