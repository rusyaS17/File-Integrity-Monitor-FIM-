package kz.kaznaru.fim.application;

import kz.kaznaru.fim.core.BaselineSnapshot;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates access to the active immutable baseline and exclusive maintenance.
 */
public final class BaselineRuntimeCoordinator {

    private final AtomicReference<BaselineSnapshot> activeSnapshot;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition operationsDrained = lock.newCondition();

    private MaintenanceState state = MaintenanceState.OPEN;
    private int activeOperations;
    private long nextTokenId;
    private MaintenanceToken activeToken;

    public BaselineRuntimeCoordinator(BaselineSnapshot initialSnapshot) {
        activeSnapshot = new AtomicReference<>(
                Objects.requireNonNull(initialSnapshot, "initialSnapshot"));
    }

    /** Returns the currently published immutable snapshot. */
    public BaselineSnapshot snapshot() {
        return activeSnapshot.get();
    }

    /**
     * Captures the current snapshot and registers an operation that must finish
     * before maintenance can proceed.
     */
    public OperationLease acquireOperation() {
        lock.lock();
        try {
            if (state != MaintenanceState.OPEN) {
                throw new IllegalStateException(
                        "Baseline operations are unavailable while state is " + state);
            }
            activeOperations = Math.incrementExact(activeOperations);
            return new OperationLease(this, activeSnapshot.get());
        } finally {
            lock.unlock();
        }
    }

    public MaintenanceState maintenanceState() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    public int activeOperationCount() {
        lock.lock();
        try {
            return activeOperations;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops admitting operations and waits for admitted leases to close.
     * A timeout or interruption cancels the transition and reopens admission.
     */
    public MaintenanceToken beginMaintenance(Duration timeout)
            throws InterruptedException, TimeoutException {
        long remainingNanos = timeoutNanos(timeout);
        lock.lockInterruptibly();
        try {
            if (state != MaintenanceState.OPEN) {
                throw new IllegalStateException(
                        "Cannot begin maintenance while state is " + state);
            }
            state = MaintenanceState.QUIESCING;

            while (activeOperations > 0) {
                if (remainingNanos <= 0) {
                    reopenAfterUnstartedMaintenance();
                    throw new TimeoutException(
                            "Timed out waiting for baseline operations to drain");
                }
                try {
                    remainingNanos = operationsDrained.awaitNanos(remainingNanos);
                } catch (InterruptedException exception) {
                    reopenAfterUnstartedMaintenance();
                    throw exception;
                }
            }

            state = MaintenanceState.MAINTENANCE;
            activeToken = new MaintenanceToken(this, ++nextTokenId, false);
            return activeToken;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Begins explicit recovery while keeping ordinary operations blocked.
     */
    public MaintenanceToken beginRecovery() {
        lock.lock();
        try {
            if (state != MaintenanceState.RECOVERY_REQUIRED) {
                throw new IllegalStateException(
                        "Recovery can only begin from RECOVERY_REQUIRED");
            }
            if (activeOperations != 0) {
                throw new IllegalStateException(
                        "Cannot recover while baseline operations are active");
            }
            state = MaintenanceState.MAINTENANCE;
            activeToken = new MaintenanceToken(this, ++nextTokenId, true);
            return activeToken;
        } finally {
            lock.unlock();
        }
    }

    /** Publishes a replacement snapshot under a regular maintenance token. */
    public void publishSnapshot(MaintenanceToken token, BaselineSnapshot newSnapshot) {
        Objects.requireNonNull(newSnapshot, "newSnapshot");
        lock.lock();
        try {
            validateActiveToken(token);
            if (token.recoveryToken) {
                throw new IllegalStateException(
                        "Recovery snapshots must be published with completeRecovery");
            }
            validateRoot(newSnapshot);
            activeSnapshot.set(newSnapshot);
            token.snapshotPublished = true;
        } finally {
            lock.unlock();
        }
    }

    /** Completes a committed replacement after its snapshot has been published. */
    public void completeMaintenance(MaintenanceToken token) {
        lock.lock();
        try {
            validateActiveToken(token);
            if (token.recoveryToken || !token.snapshotPublished) {
                throw new IllegalStateException(
                        "Maintenance requires a published replacement snapshot");
            }
            finishMaintenance(token, MaintenanceState.OPEN);
        } finally {
            lock.unlock();
        }
    }

    /** Reopens operations after a failed transaction was successfully rolled back. */
    public void reopenAfterRollback(MaintenanceToken token) {
        lock.lock();
        try {
            validateActiveToken(token);
            if (token.recoveryToken || token.snapshotPublished) {
                throw new IllegalStateException(
                        "Rollback reopening requires an unpublished maintenance token");
            }
            finishMaintenance(token, MaintenanceState.OPEN);
        } finally {
            lock.unlock();
        }
    }

    /** Blocks operations after an ambiguous or incomplete maintenance outcome. */
    public void requireRecovery(MaintenanceToken token) {
        lock.lock();
        try {
            validateActiveToken(token);
            finishMaintenance(token, MaintenanceState.RECOVERY_REQUIRED);
        } finally {
            lock.unlock();
        }
    }

    /** Publishes the verified recovered snapshot and reopens operation admission. */
    public void completeRecovery(
            MaintenanceToken token, BaselineSnapshot recoveredSnapshot) {
        Objects.requireNonNull(recoveredSnapshot, "recoveredSnapshot");
        lock.lock();
        try {
            validateActiveToken(token);
            if (!token.recoveryToken) {
                throw new IllegalStateException(
                        "A recovery token is required to complete recovery");
            }
            validateRoot(recoveredSnapshot);
            activeSnapshot.set(recoveredSnapshot);
            token.snapshotPublished = true;
            finishMaintenance(token, MaintenanceState.OPEN);
        } finally {
            lock.unlock();
        }
    }

    private void release(OperationLease lease) {
        lock.lock();
        try {
            if (lease.owner != this || lease.closed) {
                throw new IllegalStateException("Operation lease is invalid or already closed");
            }
            if (activeOperations <= 0) {
                throw new IllegalStateException("Active operation count is inconsistent");
            }
            lease.closed = true;
            activeOperations--;
            if (activeOperations == 0) {
                operationsDrained.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    private BaselineSnapshot leasedSnapshot(OperationLease lease) {
        lock.lock();
        try {
            if (lease.owner != this || lease.closed) {
                throw new IllegalStateException("Operation lease is invalid or closed");
            }
            return lease.snapshot;
        } finally {
            lock.unlock();
        }
    }

    private void abandon(MaintenanceToken token) {
        lock.lock();
        try {
            if (token.owner != this) {
                throw new IllegalStateException("Maintenance token belongs to another coordinator");
            }
            if (token.closed) {
                throw new IllegalStateException("Maintenance token is already closed");
            }
            if (token.consumed) {
                token.closed = true;
                return;
            }
            validateActiveToken(token);
            finishMaintenance(token, MaintenanceState.RECOVERY_REQUIRED);
            token.closed = true;
        } finally {
            lock.unlock();
        }
    }

    private void validateActiveToken(MaintenanceToken token) {
        if (token == null
                || token.owner != this
                || token.closed
                || token.consumed
                || activeToken != token
                || state != MaintenanceState.MAINTENANCE) {
            throw new IllegalStateException("Maintenance token is invalid or stale");
        }
    }

    private void validateRoot(BaselineSnapshot candidate) {
        if (!activeSnapshot.get().monitoredRoot().equals(candidate.monitoredRoot())) {
            throw new IllegalArgumentException(
                    "Snapshot monitored root does not match the active runtime root");
        }
    }

    private void finishMaintenance(MaintenanceToken token, MaintenanceState nextState) {
        activeToken = null;
        token.consumed = true;
        state = nextState;
        operationsDrained.signalAll();
    }

    private void reopenAfterUnstartedMaintenance() {
        state = MaintenanceState.OPEN;
        operationsDrained.signalAll();
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    public enum MaintenanceState {
        OPEN,
        QUIESCING,
        MAINTENANCE,
        RECOVERY_REQUIRED
    }

    public static final class OperationLease implements AutoCloseable {

        private final BaselineRuntimeCoordinator owner;
        private final BaselineSnapshot snapshot;
        private boolean closed;

        private OperationLease(
                BaselineRuntimeCoordinator owner, BaselineSnapshot snapshot) {
            this.owner = owner;
            this.snapshot = snapshot;
        }

        public BaselineSnapshot snapshot() {
            return owner.leasedSnapshot(this);
        }

        @Override
        public void close() {
            owner.release(this);
        }
    }

    /**
     * A token is single-use. Closing an unfinished token fails closed by
     * requiring explicit recovery.
     */
    public static final class MaintenanceToken implements AutoCloseable {

        private final BaselineRuntimeCoordinator owner;
        private final long id;
        private final boolean recoveryToken;
        private boolean snapshotPublished;
        private boolean consumed;
        private boolean closed;

        private MaintenanceToken(
                BaselineRuntimeCoordinator owner, long id, boolean recoveryToken) {
            this.owner = owner;
            this.id = id;
            this.recoveryToken = recoveryToken;
        }

        public long id() {
            return id;
        }

        @Override
        public void close() {
            owner.abandon(this);
        }
    }
}
