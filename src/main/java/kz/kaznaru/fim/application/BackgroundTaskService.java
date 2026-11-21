package kz.kaznaru.fim.application;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded application executor for queries, scans, and other blocking work. */
public final class BackgroundTaskService implements AutoCloseable {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 2;

    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public BackgroundTaskService() {
        this(2, 64);
    }

    public BackgroundTaskService(int workerCount, int queueCapacity) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        executor = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> TaskHandle<T> submit(Callable<T> operation) {
        Objects.requireNonNull(operation, "operation");
        CompletingFutureTask<T> task = new CompletingFutureTask<>(operation);
        TaskHandle<T> handle = new TaskHandle<>(task, task.completion(), executor);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            task.reject(exception);
        }
        return handle;
    }

    public TaskHandle<Void> submit(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        return submit(() -> {
            operation.run();
            return null;
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(
                    SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                cancelPendingTasks(executor.shutdownNow());
                if (!executor.awaitTermination(
                        SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Background executor did not terminate after shutdown");
                }
            }
        } catch (InterruptedException exception) {
            cancelPendingTasks(executor.shutdownNow());
            Thread.currentThread().interrupt();
        }
    }

    private void cancelPendingTasks(List<Runnable> pendingTasks) {
        for (Runnable pendingTask : pendingTasks) {
            if (pendingTask instanceof Future<?> future) {
                future.cancel(false);
            }
        }
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return operation -> {
            Thread thread = new Thread(
                    operation, "fim-background-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static final class TaskHandle<T> {

        private final FutureTask<T> task;
        private final CompletionStage<T> completion;
        private final ThreadPoolExecutor executor;

        private TaskHandle(
                FutureTask<T> task,
                CompletionStage<T> completion,
                ThreadPoolExecutor executor) {
            this.task = task;
            this.completion = completion;
            this.executor = executor;
        }

        public CompletionStage<T> completion() {
            return completion;
        }

        public boolean cancel() {
            boolean cancelled = task.cancel(true);
            if (cancelled) {
                executor.remove(task);
            }
            return cancelled;
        }

        public boolean isCancelled() {
            return task.isCancelled();
        }

        public boolean isDone() {
            return task.isDone();
        }
    }

    private static final class CompletingFutureTask<T> extends FutureTask<T> {

        private final CompletableFuture<T> completion = new CompletableFuture<>();

        private CompletingFutureTask(Callable<T> operation) {
            super(operation);
        }

        private CompletionStage<T> completion() {
            return completion.minimalCompletionStage();
        }

        private void reject(RejectedExecutionException exception) {
            setException(exception);
        }

        @Override
        protected void done() {
            if (isCancelled()) {
                completion.cancel(false);
                return;
            }
            try {
                completion.complete(get());
            } catch (CancellationException exception) {
                completion.cancel(false);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                completion.completeExceptionally(exception);
            } catch (ExecutionException exception) {
                completion.completeExceptionally(exception.getCause());
            }
        }
    }
}
