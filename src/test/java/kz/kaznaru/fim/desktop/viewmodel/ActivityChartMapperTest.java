package kz.kaznaru.fim.desktop.viewmodel;

import kz.kaznaru.fim.model.EventActivityBucket;
import kz.kaznaru.fim.model.FileStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivityChartMapperTest {

    @Test
    void mapsOnlyPersistedBucketsToLocalLabelsAndActualEventCounts() {
        List<EventActivityBucket> buckets = List.of(
                new EventActivityBucket(
                        Instant.parse("2026-03-17T12:00:00Z"),
                        Map.of(FileStatus.SAFE, 2L, FileStatus.MODIFIED, 1L)),
                new EventActivityBucket(
                        Instant.parse("2026-03-17T15:00:00Z"),
                        Map.of(FileStatus.ERROR, 1L)));

        List<ActivityChartPoint> points = ActivityChartMapper.toLocalPoints(
                buckets, ZoneId.of("America/Los_Angeles"));

        assertEquals(2, points.size());
        assertEquals("Mar 17 05:00", points.get(0).localTimeLabel());
        assertEquals(3, points.get(0).eventCount());
        assertEquals("Mar 17 08:00", points.get(1).localTimeLabel());
        assertEquals(1, points.get(1).eventCount());
    }

    @Test
    void mapsAnEmptyActivityResultToNoChartPoints() {
        assertEquals(List.of(), ActivityChartMapper.toLocalPoints(
                List.of(), ZoneId.of("UTC")));
    }
}
