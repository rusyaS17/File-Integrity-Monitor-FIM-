package kz.kaznaru.fim.model;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable event counts for a UTC time bucket containing persisted events. */
public record EventActivityBucket(
        Instant bucketStartUtc,
        Map<FileStatus, Long> eventCountsByType) {

    public EventActivityBucket {
        Objects.requireNonNull(bucketStartUtc, "bucketStartUtc");
        Objects.requireNonNull(eventCountsByType, "eventCountsByType");
        EnumMap<FileStatus, Long> copiedCounts = new EnumMap<>(FileStatus.class);
        for (Map.Entry<FileStatus, Long> entry : eventCountsByType.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalArgumentException(
                        "Activity buckets must contain positive persisted event counts");
            }
            copiedCounts.put(entry.getKey(), entry.getValue());
        }
        if (copiedCounts.isEmpty()) {
            throw new IllegalArgumentException("An activity bucket must contain an event");
        }
        eventCountsByType = Collections.unmodifiableMap(copiedCounts);
    }

    public long count(FileStatus eventType) {
        return eventCountsByType.getOrDefault(
                Objects.requireNonNull(eventType, "eventType"), 0L);
    }

    public long totalEvents() {
        return eventCountsByType.values().stream().mapToLong(Long::longValue).sum();
    }
}
