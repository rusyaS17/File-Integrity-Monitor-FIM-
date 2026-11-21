package kz.kaznaru.fim.application;

import kz.kaznaru.fim.database.FileEventRecord;
import kz.kaznaru.fim.model.EventActivityBucket;
import kz.kaznaru.fim.model.FileStatusCounts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable result of the dashboard's bounded repository queries. */
public record DashboardSnapshot(
        String monitorRoot,
        Instant fromInclusive,
        Instant toExclusive,
        Instant generatedAtUtc,
        FileStatusCounts fileStatusCounts,
        List<FileEventRecord> recentEvents,
        List<EventActivityBucket> activityBuckets) {

    public DashboardSnapshot {
        Objects.requireNonNull(monitorRoot, "monitorRoot");
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        Objects.requireNonNull(generatedAtUtc, "generatedAtUtc");
        Objects.requireNonNull(fileStatusCounts, "fileStatusCounts");
        Objects.requireNonNull(recentEvents, "recentEvents");
        Objects.requireNonNull(activityBuckets, "activityBuckets");
        if (monitorRoot.isBlank()) {
            throw new IllegalArgumentException("monitorRoot must not be blank");
        }
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
        recentEvents = List.copyOf(recentEvents);
        activityBuckets = List.copyOf(activityBuckets);
    }
}
