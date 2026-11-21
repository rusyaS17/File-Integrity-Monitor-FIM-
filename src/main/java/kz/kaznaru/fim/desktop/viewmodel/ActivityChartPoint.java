package kz.kaznaru.fim.desktop.viewmodel;

import java.util.Objects;

/** Local-time chart presentation of one persisted activity bucket. */
public record ActivityChartPoint(String localTimeLabel, long eventCount) {

    public ActivityChartPoint {
        Objects.requireNonNull(localTimeLabel, "localTimeLabel");
        if (localTimeLabel.isBlank() || eventCount <= 0) {
            throw new IllegalArgumentException("Chart points must represent persisted events");
        }
    }
}
