package kz.kaznaru.fim.desktop.viewmodel;

import kz.kaznaru.fim.model.EventActivityBucket;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Converts real UTC activity buckets into local-time chart labels. */
public final class ActivityChartMapper {

    private static final DateTimeFormatter LOCAL_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH);

    private ActivityChartMapper() {
    }

    public static List<ActivityChartPoint> toLocalPoints(
            List<EventActivityBucket> buckets,
            ZoneId timeZone) {
        Objects.requireNonNull(buckets, "buckets");
        Objects.requireNonNull(timeZone, "timeZone");
        DateTimeFormatter formatter = LOCAL_TIME_FORMAT.withZone(timeZone);
        return buckets.stream()
                .map(bucket -> new ActivityChartPoint(
                        formatter.format(bucket.bucketStartUtc()),
                        bucket.totalEvents()))
                .toList();
    }
}
