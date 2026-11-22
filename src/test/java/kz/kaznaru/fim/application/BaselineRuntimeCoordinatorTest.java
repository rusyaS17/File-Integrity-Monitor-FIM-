package kz.kaznaru.fim.application;

import kz.kaznaru.fim.core.BaselineSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BaselineRuntimeCoordinatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesWithTheProvidedImmutableSnapshot() {
        BaselineSnapshot initial = snapshot("a");
        BaselineRuntimeCoordinator coordinator = new BaselineRuntimeCoordinator(initial);

        assertSame(initial, coordinator.snapshot());
        assertEquals(BaselineRuntimeCoordinator.MaintenanceState.OPEN,
                coordinator.maintenanceState());
        assertEquals(0, coordinator.activeOperationCount());
        assertThrows(UnsupportedOperationException.class,
                () -> initial.entries().put("unexpected", hash("b")));
    }

    @Test
    void leaseCapturesOneSnapshotAndTracksActiveOperations() {
        BaselineSnapshot initial = snapshot("a");
        BaselineRuntimeCoordinator coordinator = new BaselineRuntimeCoordinator(initial);

        BaselineRuntimeCoordinator.OperationLease lease = coordinator.acquireOperation();
        assertSame(initial, lease.snapshot());
        assertEquals(1, coordinator.activeOperationCount());

        lease.close();

        assertEquals(0, coordinator.activeOperationCount());
        assertThrows(IllegalStateException.class, lease::snapshot);
    }

    @Test
    void closingAnOperationLeaseTwiceIsRejectedWithoutChangingTheCount() {
        BaselineRuntimeCoordinator coordinator =
                new BaselineRuntimeCoordinator(snapshot("a"));
        BaselineRuntimeCoordinator.OperationLease lease = coordinator.acquireOperation();
        lease.close();

        assertThrows(IllegalStateException.class, lease::close);
        assertEquals(0, coordinator.activeOperationCount());
    }

    @Test
    void quiescingRejectsNewOperationLeases() throws Exception {
        BaselineRuntimeCoordinator coordinator =
                new BaselineRuntimeCoordinator(snapshot("a"));
        BaselineRuntimeCoordinator.OperationLease lease = coordinator.acquireOperation();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BaselineRuntimeCoordinator.MaintenanceToken> maintenance =
                    executor.submit(() -> coordinator.beginMaintenance(Duration.ofSeconds(5)));
            awaitState(coordinator, BaselineRuntimeCoordinator.MaintenanceState.QUIESCING);

            assertThrows(IllegalStateException.class, coordinator::acquireOperation);
            lease.close();
            BaselineRuntimeCoordinator.MaintenanceToken token =
                    maintenance.get(2, TimeUnit.SECONDS);
            assertEquals(BaselineRuntimeCoordinator.MaintenanceState.MAINTENANCE,
                    coordinator.maintenanceState());
            coordinator.reopenAfterRollback(token);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void maintenanceWaitsForAllExistingLeasesToDrain() throws Exception {
        BaselineRuntimeCoordinator coordinator =
                new BaselineRuntimeCoordinator(snapshot("a"));
        BaselineRuntimeCoordinator.OperationLease first = coordinator.acquireOperation();
        BaselineRuntimeCoordinator.OperationLease second = coordinator.acquireOperation();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BaselineRuntimeCoordinator.MaintenanceToken> maintenance =
                    executor.submit(() -> coordinator.beginMaintenance(Duration.ofSeconds(5)));
            awaitState(coordinator, BaselineRuntimeCoordinator.MaintenanceState.QUIESCING);
            assertFalse(maintenance.isDone());
            assertEquals(2, coordinator.activeOperationCount());

            first.close();
            assertFalse(maintenance.isDone());
            second.close();

            BaselineRuntimeCoordinator.MaintenanceToken token =
                    maintenance.get(2, TimeUnit.SECONDS);
            assertEquals(0, coordinator.activeOperationCount());
            coordinator.reopenAfterRollback(token);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutCancelsQuiescingAndReopensAdmission() throws Exception {
        BaselineRuntimeCoordinator coordinator =
                new BaselineRuntimeCoordinator(snapshot("a"));
        BaselineRuntimeCoordinator.OperationLease lease = coordinator.acquireOperation();

        assertThrows(TimeoutException.class,
                () -> coordinator.beginMaintenance(Duration.ofMillis(30)));
        assertEquals(BaselineRuntimeCoordinator.MaintenanceState.OPEN,
                coordinator.maintenanceState());
        try (BaselineRuntimeCoordinator.OperationLease ignored =
                     coordinator.acquireOperation()) {
            assertEquals(2, coordinator.activeOperationCount());
        }
        lease.close();
        assertEquals(0, coordinator.activeOperationCount());
    }

    @Test
    void interruptedDrainCancelsQuiescingAndPreservesExistingLease() throws Exception {
        BaselineRuntimeCoordinator coordinator =
                new BaselineRuntimeCoordinator(snapshot("a"));
        BaselineRuntimeCoordinator.OperationLease lease = coordinator.acquireOperation();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                coordinator.beginMaintenance(Duration.ofSeconds(10));
                outcome.set(new AssertionError("Maintenance unexpectedly completed"));
            } catch (Throwable exception) {
                outcome.set(exception);
            }
        });

        waiter.start();
        awaitState(coordinator, BaselineRuntimeCoordinator.MaintenanceState.QUIESCING);
        waiter.interrupt();
        waiter.join(TimeUnit.SECONDS.toMillis(2));

        assertFalse(waiter.isAlive());
        assertInstanceOf(InterruptedException.class, outcome.get());
        assertEquals(BaselineRuntimeCoordinator.MaintenanceState.OPEN,
                coordinator.maintenanceState());
        assertEquals(1, coordinator.activeOperationCount());
        lease.close();
    }

    @Test
    void onlyAValidMaintenanceTokenCanPublishAndComplete() throws Exception {
        BaselineSnapshot initial = snapshot("a");
        BaselineSnapshot replacement = snapshot("b");
        BaselineRuntimeCoordinator coordinator =
                new BaselineRuntimeCoordinator(initial);
        BaselineRuntimeCoordinator otherCoordinator =
                new BaselineRuntimeCoordinator(initial);
        BaselineRuntimeCoordinator.MaintenanceToken token =
                coordinator.beginMaintenance(Duration.ZERO);

        assertThrows(IllegalStateException.class,
                () -> coordinator.completeMaintenance(token));
        assertThrows(IllegalStateException.class,
                () -> otherCoordinator.publishSnapshot(token, replacement));
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.publishSnapshot(token, snapshotAt(
                        temporaryDirectory.resolve("other-root"), "b")));
        assertSame(initial, coordinator.snapshot());

        coordinator.publishSnapshot(token, replacement);
        assertSame(replacement, coordinator.snapshot());
        coordinator.completeMaintenance(token);
        assertEquals(BaselineRuntimeCoordinator.MaintenanceState.OPEN,
                coordinator.maintenanceState());
        assertThrows(IllegalStateException.class,
                () -> coordinator.publishSnapshot(token, initial));
        token.close();
    }

    @Test
    void rollbackReopensWithTheOriginalSnapshot() throws Exception {
        BaselineSnapshot initial = snapshot("a");
        BaselineRuntimeCoordinator coordinator =
                new BaselineRuntimeCoordinator(initial);
        BaselineRuntimeCoordinator.MaintenanceToken token =
                coordinator.beginMaintenance(Duration.ZERO);

        coordinator.reopenAfterRollback(token);

        assertSame(initial, coordinator.snapshot());
        assertEquals(BaselineRuntimeCoordinator.MaintenanceState.OPEN,
                coordinator.maintenanceState());
        try (BaselineRuntimeCoordinator.OperationLease ignored =
                     coordinator.acquireOperation()) {
            assertEquals(initial, ignored.snapshot());
        }
    }

    @Test
    void recoveryRequiredBlocksLeasesUntilExplicitRecoveryCompletes() throws Exception {
        BaselineSnapshot initial = snapshot("a");
        BaselineSnapshot recovered = snapshot("b");
        BaselineRuntimeCoordinator coordinator =
                new BaselineRuntimeCoordinator(initial);
        BaselineRuntimeCoordinator.MaintenanceToken failedMaintenance =
                coordinator.beginMaintenance(Duration.ZERO);

        coordinator.requireRecovery(failedMaintenance);
        assertEquals(BaselineRuntimeCoordinator.MaintenanceState.RECOVERY_REQUIRED,
                coordinator.maintenanceState());
        assertThrows(IllegalStateException.class, coordinator::acquireOperation);
        assertThrows(IllegalStateException.class,
                () -> coordinator.beginMaintenance(Duration.ZERO));

        BaselineRuntimeCoordinator.MaintenanceToken recovery =
                coordinator.beginRecovery();
        assertThrows(IllegalStateException.class, coordinator::acquireOperation);
        coordinator.completeRecovery(recovery, recovered);

        assertSame(recovered, coordinator.snapshot());
        assertEquals(BaselineRuntimeCoordinator.MaintenanceState.OPEN,
                coordinator.maintenanceState());
        try (BaselineRuntimeCoordinator.OperationLease lease =
                     coordinator.acquireOperation()) {
            assertSame(recovered, lease.snapshot());
        }
    }

    @Test
    void closingAnUnfinishedMaintenanceTokenFailsClosed() throws Exception {
        BaselineRuntimeCoordinator coordinator =
                new BaselineRuntimeCoordinator(snapshot("a"));
        BaselineRuntimeCoordinator.MaintenanceToken token =
                coordinator.beginMaintenance(Duration.ZERO);

        token.close();

        assertEquals(BaselineRuntimeCoordinator.MaintenanceState.RECOVERY_REQUIRED,
                coordinator.maintenanceState());
        assertThrows(IllegalStateException.class, coordinator::acquireOperation);
        assertThrows(IllegalStateException.class, token::close);
    }

    @Test
    void concurrentOperationsAndMaintenanceKeepSnapshotsAndCountsConsistent()
            throws Exception {
        BaselineSnapshot firstSnapshot = snapshot("a");
        BaselineSnapshot secondSnapshot = snapshot("b");
        BaselineRuntimeCoordinator coordinator =
                new BaselineRuntimeCoordinator(firstSnapshot);
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch firstOperation = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger observations = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            for (int worker = 0; worker < 4; worker++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        assertTrue(start.await(2, TimeUnit.SECONDS));
                        for (int attempt = 0; attempt < 1500; attempt++) {
                            BaselineRuntimeCoordinator.OperationLease lease;
                            try {
                                lease = coordinator.acquireOperation();
                            } catch (IllegalStateException expectedDuringMaintenance) {
                                continue;
                            }
                            try (lease) {
                                firstOperation.countDown();
                                String observedHash = lease.snapshot().entries().values()
                                        .stream().findFirst().orElseThrow();
                                assertTrue(observedHash.equals(hash("a"))
                                        || observedHash.equals(hash("b")));
                                observations.incrementAndGet();
                            }
                        }
                    } catch (Throwable exception) {
                        failure.compareAndSet(null, exception);
                    }
                });
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(firstOperation.await(2, TimeUnit.SECONDS));

            for (int iteration = 0; iteration < 12; iteration++) {
                BaselineRuntimeCoordinator.MaintenanceToken token =
                        coordinator.beginMaintenance(Duration.ofSeconds(5));
                BaselineSnapshot next =
                        iteration % 2 == 0 ? secondSnapshot : firstSnapshot;
                coordinator.publishSnapshot(token, next);
                coordinator.completeMaintenance(token);
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(observations.get() > 0);
            assertEquals(0, coordinator.activeOperationCount());
            assertEquals(BaselineRuntimeCoordinator.MaintenanceState.OPEN,
                    coordinator.maintenanceState());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void awaitState(
            BaselineRuntimeCoordinator coordinator,
            BaselineRuntimeCoordinator.MaintenanceState expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (coordinator.maintenanceState() != expected
                && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(1);
        }
        assertEquals(expected, coordinator.maintenanceState());
    }

    private BaselineSnapshot snapshot(String hash) {
        Path root = temporaryDirectory.toAbsolutePath().normalize();
        return snapshotAt(root, hash);
    }

    private BaselineSnapshot snapshotAt(Path root, String hash) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path file = normalizedRoot.resolve("tracked.txt");
        return new BaselineSnapshot(normalizedRoot,
                Map.of(file.toString(), hash(hash)));
    }

    private String hash(String character) {
        return character.repeat(64);
    }
}
