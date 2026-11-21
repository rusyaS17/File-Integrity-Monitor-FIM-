package kz.kaznaru.fim.application;

import kz.kaznaru.fim.core.FileWatchEvent;
import kz.kaznaru.fim.core.FileWatcher;
import kz.kaznaru.fim.core.WatchEventDebouncer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Owns watcher, debounce, and reconciliation lifecycles for one monitored root. */
public final class MonitoringService implements AutoCloseable {

    private static final ConcurrentHashMap<Path, MonitoringService> ACTIVE_ROOTS =
            new ConcurrentHashMap<>();

    private final Path monitoredRoot;
    private final EventProcessor eventProcessor;
    private final Duration debounceDuration;
    private final Duration reconciliationInterval;
    private final Consumer<Throwable> errorHandler;
    private final CopyOnWriteArrayList<Consumer<Throwable>> errorListeners =
            new CopyOnWriteArrayList<>();
    private final Object processingLock = new Object();

    private volatile boolean running;
    private volatile Throwable lastFailure;
    private FileWatcher fileWatcher;
    private WatchEventDebouncer debouncer;
    private ExecutorService watcherExecutor;
    private ScheduledExecutorService reconciliationExecutor;

    public MonitoringService(
            Path monitoredRoot,
            EventProcessor eventProcessor,
            Duration debounceDuration,
            Duration reconciliationInterval) {
        this(monitoredRoot, eventProcessor, debounceDuration, reconciliationInterval,
                failure -> System.err.println("Monitoring error: " + failure.getMessage()));
    }

    public MonitoringService(
            Path monitoredRoot,
            EventProcessor eventProcessor,
            Duration debounceDuration,
            Duration reconciliationInterval,
            Consumer<Throwable> errorHandler) {
        this.monitoredRoot = Objects.requireNonNull(monitoredRoot, "monitoredRoot")
                .toAbsolutePath().normalize();
        this.eventProcessor = Objects.requireNonNull(eventProcessor, "eventProcessor");
        this.debounceDuration = requireNonNegative(debounceDuration, "debounceDuration");
        this.reconciliationInterval = requirePositive(
                reconciliationInterval, "reconciliationInterval");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        if (!Files.isDirectory(monitoredRoot)) {
            throw new IOException("Monitored root is not a directory: " + monitoredRoot);
        }
        MonitoringService active = ACTIVE_ROOTS.putIfAbsent(monitoredRoot, this);
        if (active != null && active != this) {
            throw new IllegalStateException(
                    "A monitoring service is already running for: " + monitoredRoot);
        }

        try {
            fileWatcher = new FileWatcher(monitoredRoot);
            debouncer = new WatchEventDebouncer(debounceDuration);
            watcherExecutor = Executors.newSingleThreadExecutor(
                    namedThreadFactory("fim-watcher-" + threadSuffix()));
            reconciliationExecutor = Executors.newSingleThreadScheduledExecutor(
                    namedThreadFactory("fim-reconcile-" + threadSuffix()));
            running = true;

            watcherExecutor.submit(() -> {
                try {
                    fileWatcher.watch(event -> debouncer.submit(event, this::processEvent));
                } catch (IOException | RuntimeException exception) {
                    if (running) {
                        reportFailure(exception);
                    }
                }
            });

            reconciliationExecutor.scheduleWithFixedDelay(
                    this::reconcileSafely,
                    0,
                    reconciliationInterval.toNanos(),
                    TimeUnit.NANOSECONDS);
        } catch (IOException | RuntimeException exception) {
            running = false;
            closeResources();
            ACTIVE_ROOTS.remove(monitoredRoot, this);
            throw exception;
        }
    }

    public void stop() {
        synchronized (this) {
            if (!running && fileWatcher == null && debouncer == null
                    && watcherExecutor == null && reconciliationExecutor == null) {
                ACTIVE_ROOTS.remove(monitoredRoot, this);
                return;
            }
            boolean reconcileFinalState = running;
            running = false;
            closeResources();
            if (reconcileFinalState) {
                synchronized (processingLock) {
                    try {
                        eventProcessor.reconcile();
                    } catch (IOException | java.sql.SQLException | RuntimeException exception) {
                        reportFailure(exception);
                    }
                }
            }
            ACTIVE_ROOTS.remove(monitoredRoot, this);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void addErrorListener(Consumer<Throwable> listener) {
        errorListeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    public boolean removeErrorListener(Consumer<Throwable> listener) {
        return errorListeners.remove(Objects.requireNonNull(listener, "listener"));
    }

    public Throwable lastFailure() {
        return lastFailure;
    }

    public int registeredDirectoryCount() {
        FileWatcher watcher = fileWatcher;
        return watcher == null ? 0 : watcher.registeredDirectoryCount();
    }

    private void processEvent(FileWatchEvent event) {
        synchronized (processingLock) {
            if (!running) {
                return;
            }
            try {
                eventProcessor.process(event);
            } catch (IOException | java.sql.SQLException | RuntimeException exception) {
                reportFailure(exception);
            }
        }
    }

    private void reconcileSafely() {
        synchronized (processingLock) {
            if (!running) {
                return;
            }
            try {
                fileWatcher.registerExistingDirectories();
            } catch (IOException | RuntimeException exception) {
                reportFailure(exception);
            }
            try {
                eventProcessor.reconcile();
            } catch (IOException | java.sql.SQLException | RuntimeException exception) {
                reportFailure(exception);
            }
        }
    }

    private void reportFailure(Throwable failure) {
        lastFailure = failure;
        try {
            errorHandler.accept(failure);
        } catch (RuntimeException handlerFailure) {
            if (handlerFailure != failure) {
                failure.addSuppressed(handlerFailure);
            }
            System.err.println("Monitoring error handler failed: " + handlerFailure.getMessage());
        }
        for (Consumer<Throwable> listener : errorListeners) {
            try {
                listener.accept(failure);
            } catch (RuntimeException listenerFailure) {
                if (listenerFailure != failure) {
                    failure.addSuppressed(listenerFailure);
                }
                System.err.println(
                        "Monitoring error listener failed: " + listenerFailure.getMessage());
            }
        }
    }

    private void closeResources() {
        if (reconciliationExecutor != null) {
            reconciliationExecutor.shutdownNow();
        }
        if (fileWatcher != null) {
            try {
                fileWatcher.close();
            } catch (IOException exception) {
                reportFailure(exception);
            }
        }
        if (debouncer != null) {
            debouncer.close();
        }
        if (watcherExecutor != null) {
            watcherExecutor.shutdown();
        }
        awaitTermination(reconciliationExecutor, "reconciliation");
        awaitTermination(watcherExecutor, "watcher");

        reconciliationExecutor = null;
        watcherExecutor = null;
        debouncer = null;
        fileWatcher = null;
    }

    private void awaitTermination(ExecutorService executor, String name) {
        if (executor == null) {
            return;
        }
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    reportFailure(new IllegalStateException(
                            "Timed out while stopping " + name + " executor"));
                }
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            reportFailure(exception);
        }
    }

    private static ThreadFactory namedThreadFactory(String name) {
        AtomicInteger number = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, name + "-" + number.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    private String threadSuffix() {
        return Integer.toHexString(System.identityHashCode(this));
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    @Override
    public void close() {
        stop();
    }
}
