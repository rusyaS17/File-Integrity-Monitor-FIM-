package kz.kaznaru.fim.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundTaskServiceTest {

    @Test
    void completesTasksOnManagedWorkersAndPropagatesFailures() throws Exception {
        try (BackgroundTaskService service = new BackgroundTaskService(1, 4)) {
            BackgroundTaskService.TaskHandle<String> success = service.submit(
                    () -> Thread.currentThread().getName());
            assertTrue(success.completion().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS).startsWith("fim-background-"));

            BackgroundTaskService.TaskHandle<String> failure = service.submit(() -> {
                throw new IllegalStateException("query failed");
            });
            ExecutionException executionException = assertThrows(
                    ExecutionException.class,
                    () -> failure.completion().toCompletableFuture()
                            .get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, executionException.getCause());
            assertEquals("query failed", executionException.getCause().getMessage());
        }
    }

    @Test
    void supportsCancellationOfRunningTasks() throws Exception {
        try (BackgroundTaskService service = new BackgroundTaskService(1, 2)) {
            CountDownLatch taskStarted = new CountDownLatch(1);
            BackgroundTaskService.TaskHandle<Void> task = service.submit(() -> {
                taskStarted.countDown();
                new CountDownLatch(1).await();
                return null;
            });

            assertTrue(taskStarted.await(2, TimeUnit.SECONDS));
            assertTrue(task.cancel());
            assertTrue(task.isCancelled());
            assertTrue(task.completion().toCompletableFuture().isCompletedExceptionally());
        }
    }

    @Test
    void shutdownIsIdempotentAndNewTasksReportRejection() throws Exception {
        BackgroundTaskService service = new BackgroundTaskService(1, 2);

        service.close();
        service.close();
        BackgroundTaskService.TaskHandle<Void> rejected = service.submit(() -> { });

        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> rejected.completion().toCompletableFuture()
                        .get(2, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
    }
}
