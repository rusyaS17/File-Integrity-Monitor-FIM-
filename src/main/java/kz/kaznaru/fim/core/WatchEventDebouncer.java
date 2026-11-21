package kz.kaznaru.fim.core;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.nio.file.Path;

/** Coalesces rapid MODIFY events while allowing later changes through. */
public final class WatchEventDebouncer implements AutoCloseable {

    private final Object lock = new Object();
    private final Duration debounceDuration;
    private final ScheduledExecutorService scheduler;
    private final Map<Path, PendingEvent> pendingEvents = new HashMap<>();
    private boolean closed;

    public WatchEventDebouncer(Duration debounceDuration) {
        this.debounceDuration = Objects.requireNonNull(debounceDuration, "debounceDuration");
        if (debounceDuration.isNegative()) {
            throw new IllegalArgumentException("Debounce duration must not be negative");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "fim-event-debouncer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void submit(FileWatchEvent event, Consumer<FileWatchEvent> handler) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(handler, "handler");
        if (event.kind() != FileWatchEvent.Kind.MODIFY) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                cancelPending(event);
            }
            handler.accept(event);
            return;
        }

        synchronized (lock) {
            if (closed) {
                return;
            }
            Path path = event.path().toAbsolutePath().normalize();
            PendingEvent pending = new PendingEvent(event, handler);
            PendingEvent previous = pendingEvents.put(path, pending);
            if (previous != null && previous.future != null) {
                previous.future.cancel(false);
            }
            pending.future = scheduler.schedule(
                    () -> dispatchIfCurrent(path, pending),
                    debounceDuration.toNanos(),
                    TimeUnit.NANOSECONDS);
        }
    }

    private void cancelPending(FileWatchEvent event) {
        if (event.path() == null) {
            return;
        }
        Path path = event.path().toAbsolutePath().normalize();
        PendingEvent pending = pendingEvents.remove(path);
        if (pending != null && pending.future != null) {
            pending.future.cancel(false);
        }
    }

    private void dispatchIfCurrent(Path path, PendingEvent pending) {
        synchronized (lock) {
            if (closed || !pendingEvents.remove(path, pending)) {
                return;
            }
        }
        pending.handler.accept(pending.event);
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            for (PendingEvent pending : pendingEvents.values()) {
                if (pending.future != null) {
                    pending.future.cancel(false);
                }
            }
            pendingEvents.clear();
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException exception) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class PendingEvent {
        private final FileWatchEvent event;
        private final Consumer<FileWatchEvent> handler;
        private ScheduledFuture<?> future;

        private PendingEvent(FileWatchEvent event, Consumer<FileWatchEvent> handler) {
            this.event = event;
            this.handler = handler;
        }
    }
}
