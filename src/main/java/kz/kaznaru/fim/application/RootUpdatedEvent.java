package kz.kaznaru.fim.application;

import java.time.Instant;
import java.util.Objects;

/** Immutable notification that a monitored root was successfully persisted. */
public record RootUpdatedEvent(String monitorRoot, Instant timestampUtc) {

    public RootUpdatedEvent {
        Objects.requireNonNull(monitorRoot, "monitorRoot");
        Objects.requireNonNull(timestampUtc, "timestampUtc");
        if (monitorRoot.isBlank()) {
            throw new IllegalArgumentException("monitorRoot must not be blank");
        }
    }
}
