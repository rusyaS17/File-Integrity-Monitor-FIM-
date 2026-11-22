package kz.kaznaru.fim.core;

import kz.kaznaru.fim.application.ApplicationConfiguration;
import kz.kaznaru.fim.desktop.bootstrap.DesktopServiceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BaselineFileTransactionTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void initialCommitWritesLoadableV2Baseline() throws Exception {
        Path monitored = createMonitoredDirectory("initial");
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "file.txt", "initial");
        Path primary = temporaryDirectory.resolve("initial-baseline.txt");

        BaselineFileTransaction.CommitResult result =
                new BaselineFileTransaction().commit(snapshot, primary);

        assertTrue(result.committed());
        assertTrue(result.cleanupWarnings().isEmpty());
        assertTrue(Files.readString(primary).startsWith(
                BaselineManager.VERSION_HEADER + System.lineSeparator()));
        assertFalse(Files.exists(backupOf(primary)));
        assertFalse(Files.exists(journalOf(primary)));
        BaselineManager loaded = new BaselineManager();
        loaded.loadBaselineFromFile(primary, monitored);
        assertEquals(snapshot.entries(), loaded.getBaselineEntries());
        assertNoTransactionFiles(primary.getParent());
        assertFalse(Files.exists(journalOf(primary)));
    }

    @Test
    void successfulReplacementMovesPreviousPrimaryToBackup() throws Exception {
        Path monitored = createMonitoredDirectory("replacement");
        Path primary = temporaryDirectory.resolve("replacement-baseline.txt");
        Path backup = backupOf(primary);
        byte[] previousPrimary = "previous primary bytes".getBytes(StandardCharsets.UTF_8);
        byte[] previousBackup = "older backup bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, previousPrimary);
        Files.write(backup, previousBackup);
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "new baseline");

        new BaselineFileTransaction().commit(snapshot, primary);

        assertArrayEquals(previousPrimary, Files.readAllBytes(backup));
        assertFalse(Arrays.equals(previousBackup, Files.readAllBytes(backup)));
        assertFalse(Files.exists(journalOf(primary)));
        BaselineManager loaded = new BaselineManager();
        loaded.loadBaselineFromFile(primary, monitored);
        assertEquals(snapshot.entries(), loaded.getBaselineEntries());
        assertNoTransactionFiles(primary.getParent());
    }

    @Test
    void backupRemainsUntouchedUntilPrimaryReplacementIsAttempted() throws Exception {
        Path monitored = createMonitoredDirectory("backup-until-commit");
        Path primary = temporaryDirectory.resolve("backup-until-commit.txt");
        Path backup = backupOf(primary);
        byte[] previousPrimary = "primary before".getBytes(StandardCharsets.UTF_8);
        byte[] previousBackup = "backup before".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, previousPrimary);
        Files.write(backup, previousBackup);
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.watchBackupDuringPrimaryMove(backup, previousBackup);
        operations.failMove(primary, 1);

        BaselineFileTransaction.TransactionException failure = assertThrows(
                BaselineFileTransaction.TransactionException.class,
                () -> new BaselineFileTransaction(operations).commit(snapshot, primary));

        assertEquals(BaselineFileTransaction.Phase.PRIMARY_REPLACEMENT, failure.phase());
        assertTrue(operations.backupMatchedAtPrimaryMove);
        assertArrayEquals(previousPrimary, Files.readAllBytes(primary));
        assertArrayEquals(previousBackup, Files.readAllBytes(backup));
    }

    @Test
    void stagingFailureLeavesBothExistingFilesByteForByteUnchanged() throws Exception {
        Path monitored = createMonitoredDirectory("staging-failure");
        Path primary = temporaryDirectory.resolve("staging-failure.txt");
        Path backup = backupOf(primary);
        byte[] oldPrimary = "old primary".getBytes(StandardCharsets.UTF_8);
        byte[] oldBackup = "old backup".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, oldPrimary);
        Files.write(backup, oldBackup);
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.failWrite = true;
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");

        BaselineFileTransaction.TransactionException failure = assertThrows(
                BaselineFileTransaction.TransactionException.class,
                () -> new BaselineFileTransaction(operations).commit(snapshot, primary));

        assertEquals(BaselineFileTransaction.Phase.STAGING, failure.phase());
        assertNull(failure.rollbackFailure());
        assertArrayEquals(oldPrimary, Files.readAllBytes(primary));
        assertArrayEquals(oldBackup, Files.readAllBytes(backup));
        assertTrue(failure.recoveryArtifacts().isEmpty());
    }

    @Test
    void backupReplacementFailureRollsBackBothOriginalFilesExactly() throws Exception {
        Path monitored = createMonitoredDirectory("backup-failure");
        Path primary = temporaryDirectory.resolve("backup-failure.txt");
        Path backup = backupOf(primary);
        byte[] oldPrimary = "old primary bytes".getBytes(StandardCharsets.UTF_8);
        byte[] oldBackup = "old backup bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, oldPrimary);
        Files.write(backup, oldBackup);
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.failAfterMove(backup, 1);
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");

        BaselineFileTransaction.TransactionException failure = assertThrows(
                BaselineFileTransaction.TransactionException.class,
                () -> new BaselineFileTransaction(operations).commit(snapshot, primary));

        assertEquals(BaselineFileTransaction.Phase.BACKUP_REPLACEMENT, failure.phase());
        assertNotNull(failure.operationFailure());
        assertNull(failure.rollbackFailure());
        assertArrayEquals(oldPrimary, Files.readAllBytes(primary));
        assertArrayEquals(oldBackup, Files.readAllBytes(backup));
        assertTrue(failure.recoveryArtifacts().isEmpty());
        assertNoTransactionFiles(primary.getParent());
    }

    @Test
    void primaryReplacementFailureRestoresOriginalPrimaryAndBackup() throws Exception {
        Path monitored = createMonitoredDirectory("primary-failure");
        Path primary = temporaryDirectory.resolve("primary-failure.txt");
        Path backup = backupOf(primary);
        byte[] oldPrimary = "old primary bytes".getBytes(StandardCharsets.UTF_8);
        byte[] oldBackup = "old backup bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, oldPrimary);
        Files.write(backup, oldBackup);
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.failMove(primary, 1);
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");

        BaselineFileTransaction.TransactionException failure = assertThrows(
                BaselineFileTransaction.TransactionException.class,
                () -> new BaselineFileTransaction(operations).commit(snapshot, primary));

        assertEquals(BaselineFileTransaction.Phase.PRIMARY_REPLACEMENT, failure.phase());
        assertNull(failure.rollbackFailure());
        assertArrayEquals(oldPrimary, Files.readAllBytes(primary));
        assertArrayEquals(oldBackup, Files.readAllBytes(backup));
        assertNoTransactionFiles(primary.getParent());
    }

    @Test
    void rollbackFailureReportsFailureAndPreservesRecoveryArtifacts() throws Exception {
        Path monitored = createMonitoredDirectory("rollback-failure");
        Path primary = temporaryDirectory.resolve("rollback-failure.txt");
        Path backup = backupOf(primary);
        byte[] oldPrimary = "recover this primary".getBytes(StandardCharsets.UTF_8);
        byte[] oldBackup = "preserve this backup".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, oldPrimary);
        Files.write(backup, oldBackup);
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.failAfterMove(backup, 1);
        operations.failMove(primary, 2);
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");

        BaselineFileTransaction.TransactionException failure = assertThrows(
                BaselineFileTransaction.TransactionException.class,
                () -> new BaselineFileTransaction(operations).commit(snapshot, primary));

        assertEquals(BaselineFileTransaction.Phase.BACKUP_REPLACEMENT, failure.phase());
        assertNotNull(failure.operationFailure());
        assertNotNull(failure.rollbackFailure());
        assertFalse(failure.recoveryArtifacts().isEmpty());
        assertTrue(failure.recoveryArtifacts().stream().allMatch(Files::exists));
        assertTrue(failure.recoveryArtifacts().stream()
                .anyMatch(path -> hasBytes(path, oldPrimary)));
        assertArrayEquals(oldBackup, Files.readAllBytes(backup));
    }

    @Test
    void missingPrimaryAndBackupCombinationsAreHandledSafely() throws Exception {
        assertMissingCombination("neither", false, false);
        assertMissingCombination("primary-only", true, false);
        assertMissingCombination("backup-only", false, true);
        assertMissingCombination("both", true, true);
    }

    @Test
    void cleanupFailureAfterCommitIsReturnedAsWarning() throws Exception {
        Path monitored = createMonitoredDirectory("cleanup-warning");
        Path primary = temporaryDirectory.resolve("cleanup-warning.txt");
        Path backup = backupOf(primary);
        byte[] oldPrimary = "old primary".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, oldPrimary);
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.failDeleteSuffix = ".previous-primary";

        BaselineFileTransaction.CommitResult result =
                new BaselineFileTransaction(operations).commit(snapshot, primary);

        assertTrue(result.committed());
        assertEquals(1, result.cleanupWarnings().size());
        assertTrue(Files.exists(result.cleanupWarnings().get(0).path()));
        assertArrayEquals(oldPrimary, Files.readAllBytes(backup));
        BaselineManager loaded = new BaselineManager();
        loaded.loadBaselineFromFile(primary, monitored);
        assertEquals(snapshot.entries(), loaded.getBaselineEntries());
    }

    @Test
    void legacyPrimaryIsPreservedAsBackupAndNewPrimaryRemainsV2Compatible()
            throws Exception {
        Path monitored = createMonitoredDirectory("legacy-compatibility");
        Path legacyFile = monitored.resolve("legacy.txt");
        Files.writeString(legacyFile, "legacy contents");
        String oldHash = new HashCalculator().calculateSHA256(legacyFile);
        String encodedPath = Base64.getUrlEncoder().withoutPadding().encodeToString(
                legacyFile.toAbsolutePath().normalize().toString()
                        .getBytes(StandardCharsets.UTF_8));
        byte[] legacyBytes = (encodedPath + "|" + oldHash + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        Path primary = temporaryDirectory.resolve("legacy-baseline.txt");
        Files.write(primary, legacyBytes);

        Files.writeString(legacyFile, "new contents");
        BaselineSnapshot snapshot = new BaselineManager().prepareBaseline(monitored);
        new BaselineFileTransaction().commit(snapshot, primary);

        assertArrayEquals(legacyBytes, Files.readAllBytes(backupOf(primary)));
        assertTrue(Files.readString(primary).startsWith(
                BaselineManager.VERSION_HEADER + System.lineSeparator()));
        BaselineManager loadedPrimary = new BaselineManager();
        loadedPrimary.loadBaselineFromFile(primary, monitored);
        assertEquals(snapshot.entries(), loadedPrimary.getBaselineEntries());
        BaselineManager loadedLegacyBackup = new BaselineManager();
        loadedLegacyBackup.loadBaselineFromFile(backupOf(primary), monitored);
        assertEquals(oldHash, loadedLegacyBackup.getBaselineEntries().get(
                legacyFile.toAbsolutePath().normalize().toString()));
    }

    @Test
    void crashAfterJournalCreationIsDetectedAndRecoverable() throws Exception {
        Path monitored = createMonitoredDirectory("crash-after-journal");
        Path primary = temporaryDirectory.resolve("crash-after-journal.txt");
        Path backup = backupOf(primary);
        byte[] oldPrimary = "original primary".getBytes(StandardCharsets.UTF_8);
        byte[] oldBackup = "original backup".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, oldPrimary);
        Files.write(backup, oldBackup);
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.crashAfterMove(journalOf(primary), 1);
        BaselineFileTransaction transaction = new BaselineFileTransaction(operations);

        assertThrows(SimulatedCrash.class, () -> transaction.commit(snapshot, primary));

        assertEquals(BaselineFileTransaction.RecoveryStatus.RECOVERY_REQUIRED,
                transaction.inspectRecovery(primary).status());
        BaselineFileTransaction.RecoveryResult result =
                new BaselineFileTransaction().recover(primary);

        assertEquals(BaselineFileTransaction.RecoveryOutcome.ROLLED_BACK, result.outcome());
        assertArrayEquals(oldPrimary, Files.readAllBytes(primary));
        assertArrayEquals(oldBackup, Files.readAllBytes(backup));
        assertFalse(Files.exists(journalOf(primary)));
        assertNoTransactionFiles(primary.getParent());
    }

    @Test
    void desktopStartupDetectsPendingRecoveryBeforeLoadingApplicationServices()
            throws Exception {
        ApplicationConfiguration configuration =
                ApplicationConfiguration.forDirectory(
                        temporaryDirectory.resolve("startup-recovery"));
        Files.createDirectories(configuration.monitoredDirectory());
        Path monitoredFile = configuration.monitoredDirectory().resolve("sample.txt");
        Files.writeString(monitoredFile, "candidate");
        Path primary = configuration.baselineFile();
        Path backup = backupOf(primary);
        byte[] oldPrimary = "old primary".getBytes(StandardCharsets.UTF_8);
        byte[] oldBackup = "old backup".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, oldPrimary);
        Files.write(backup, oldBackup);
        BaselineSnapshot snapshot = new BaselineManager()
                .prepareBaseline(configuration.monitoredDirectory());
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.crashAfterMove(primary, 1);
        assertThrows(SimulatedCrash.class,
                () -> new BaselineFileTransaction(operations).commit(snapshot, primary));

        IOException startupFailure = assertThrows(IOException.class,
                () -> new DesktopServiceContext(configuration));

        assertTrue(startupFailure.getMessage().contains("Unfinished baseline transaction"));
        assertFalse(Files.exists(configuration.databaseFile()));
        assertTrue(Files.exists(journalOf(primary)));
        new BaselineFileTransaction().recover(primary);
        assertArrayEquals(oldPrimary, Files.readAllBytes(primary));
        assertArrayEquals(oldBackup, Files.readAllBytes(backup));
        assertFalse(Files.exists(journalOf(primary)));
        assertNoTransactionFiles(primary.getParent());
    }

    @Test
    void crashAfterPrimaryReplacementIsDetectedAndRecoverable() throws Exception {
        Path monitored = createMonitoredDirectory("crash-after-primary");
        Path primary = temporaryDirectory.resolve("crash-after-primary.txt");
        Path backup = backupOf(primary);
        byte[] oldPrimary = "original primary".getBytes(StandardCharsets.UTF_8);
        byte[] oldBackup = "original backup".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, oldPrimary);
        Files.write(backup, oldBackup);
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.crashAfterMove(primary, 1);

        assertThrows(SimulatedCrash.class,
                () -> new BaselineFileTransaction(operations).commit(snapshot, primary));

        BaselineFileTransaction recovery = new BaselineFileTransaction();
        assertTrue(recovery.hasUnfinishedTransaction(primary));
        assertEquals(BaselineFileTransaction.RecoveryOutcome.ROLLED_BACK,
                recovery.recover(primary).outcome());
        assertArrayEquals(oldPrimary, Files.readAllBytes(primary));
        assertArrayEquals(oldBackup, Files.readAllBytes(backup));
    }

    @Test
    void crashAfterBackupReplacementRestoresBothOriginalFiles() throws Exception {
        Path monitored = createMonitoredDirectory("crash-after-backup");
        Path primary = temporaryDirectory.resolve("crash-after-backup.txt");
        Path backup = backupOf(primary);
        byte[] oldPrimary = "original primary".getBytes(StandardCharsets.UTF_8);
        byte[] oldBackup = "original backup".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, oldPrimary);
        Files.write(backup, oldBackup);
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.crashAfterMove(backup, 1);

        assertThrows(SimulatedCrash.class,
                () -> new BaselineFileTransaction(operations).commit(snapshot, primary));

        BaselineFileTransaction.RecoveryResult result =
                new BaselineFileTransaction().recover(primary);

        assertEquals(BaselineFileTransaction.RecoveryOutcome.ROLLED_BACK, result.outcome());
        assertArrayEquals(oldPrimary, Files.readAllBytes(primary));
        assertArrayEquals(oldBackup, Files.readAllBytes(backup));
        assertFalse(Files.exists(journalOf(primary)));
        assertNoTransactionFiles(primary.getParent());
    }

    @Test
    void crashAfterCommittedJournalFinalizesWithoutRollingBack() throws Exception {
        Path monitored = createMonitoredDirectory("crash-after-committed");
        Path primary = temporaryDirectory.resolve("crash-after-committed.txt");
        Path backup = backupOf(primary);
        byte[] oldPrimary = "original primary".getBytes(StandardCharsets.UTF_8);
        byte[] oldBackup = "original backup".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, oldPrimary);
        Files.write(backup, oldBackup);
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.crashAfterMove(journalOf(primary), 3);

        assertThrows(SimulatedCrash.class,
                () -> new BaselineFileTransaction(operations).commit(snapshot, primary));
        BaselineFileTransaction recovery = new BaselineFileTransaction();

        assertEquals(BaselineFileTransaction.RecoveryStatus.COMMIT_FINALIZATION_REQUIRED,
                recovery.inspectRecovery(primary).status());
        assertEquals(BaselineFileTransaction.RecoveryOutcome.COMMIT_FINALIZED,
                recovery.recover(primary).outcome());
        BaselineManager loaded = new BaselineManager();
        loaded.loadBaselineFromFile(primary, monitored);
        assertEquals(snapshot.entries(), loaded.getBaselineEntries());
        assertArrayEquals(oldPrimary, Files.readAllBytes(backup));
        assertFalse(Files.exists(journalOf(primary)));
        assertNoTransactionFiles(primary.getParent());
    }

    @Test
    void recoveryIsIdempotentWhenInterruptedAfterRestoringPrimary() throws Exception {
        Path monitored = createMonitoredDirectory("interrupted-recovery");
        Path primary = temporaryDirectory.resolve("interrupted-recovery.txt");
        Path backup = backupOf(primary);
        byte[] oldPrimary = "original primary".getBytes(StandardCharsets.UTF_8);
        byte[] oldBackup = "original backup".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, oldPrimary);
        Files.write(backup, oldBackup);
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.crashAfterMove(primary, 1);
        assertThrows(SimulatedCrash.class,
                () -> new BaselineFileTransaction(operations).commit(snapshot, primary));
        operations.crashAfterMove(primary, 2);

        assertThrows(SimulatedCrash.class,
                () -> new BaselineFileTransaction(operations).recover(primary));
        BaselineFileTransaction restarted = new BaselineFileTransaction();

        assertEquals(BaselineFileTransaction.RecoveryOutcome.ROLLED_BACK,
                restarted.recover(primary).outcome());
        assertEquals(BaselineFileTransaction.RecoveryOutcome.NONE,
                restarted.recover(primary).outcome());
        assertArrayEquals(oldPrimary, Files.readAllBytes(primary));
        assertArrayEquals(oldBackup, Files.readAllBytes(backup));
        assertFalse(Files.exists(journalOf(primary)));
        assertNoTransactionFiles(primary.getParent());
    }

    @Test
    void recoveryHandlesAllOriginalPrimaryAndBackupExistenceCombinations()
            throws Exception {
        recoverMissingCombination("neither", false, false);
        recoverMissingCombination("primary-only", true, false);
        recoverMissingCombination("backup-only", false, true);
        recoverMissingCombination("both", true, true);
    }

    @Test
    void corruptJournalIsReportedWithoutDeletingAnyArtifacts() throws Exception {
        Path primary = temporaryDirectory.resolve("corrupt-journal.txt");
        Path journal = journalOf(primary);
        Path artifact = temporaryDirectory.resolve(
                primary.getFileName() + ".fim-txn-manual-recovery");
        byte[] originalPrimary = "keep this file".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, originalPrimary);
        Files.writeString(journal, "#FIM_BASELINE_TRANSACTION_V1\nstate=CORRUPT\n");
        Files.writeString(artifact, "preserve this artifact");
        BaselineFileTransaction transaction = new BaselineFileTransaction();

        BaselineFileTransaction.RecoveryInspection inspection =
                transaction.inspectRecovery(primary);
        BaselineFileTransaction.RecoveryException failure = assertThrows(
                BaselineFileTransaction.RecoveryException.class,
                () -> transaction.recover(primary));

        assertEquals(BaselineFileTransaction.RecoveryStatus.CORRUPT_JOURNAL,
                inspection.status());
        assertEquals(BaselineFileTransaction.RecoveryStatus.CORRUPT_JOURNAL,
                failure.status());
        assertTrue(failure.recoveryArtifacts().contains(journal));
        assertTrue(failure.recoveryArtifacts().contains(artifact));
        assertArrayEquals(originalPrimary, Files.readAllBytes(primary));
        assertTrue(Files.exists(journal));
        assertTrue(Files.exists(artifact));
    }

    @Test
    void recoveryFailurePreservesJournalAndRecoveryFiles() throws Exception {
        Path monitored = createMonitoredDirectory("recovery-failure");
        Path primary = temporaryDirectory.resolve("recovery-failure.txt");
        Path backup = backupOf(primary);
        byte[] oldPrimary = "original primary".getBytes(StandardCharsets.UTF_8);
        byte[] oldBackup = "original backup".getBytes(StandardCharsets.UTF_8);
        Files.write(primary, oldPrimary);
        Files.write(backup, oldBackup);
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.crashAfterMove(primary, 1);
        assertThrows(SimulatedCrash.class,
                () -> new BaselineFileTransaction(operations).commit(snapshot, primary));
        operations.failMove(primary, 2);

        BaselineFileTransaction.RecoveryException failure = assertThrows(
                BaselineFileTransaction.RecoveryException.class,
                () -> new BaselineFileTransaction(operations).recover(primary));

        assertEquals(BaselineFileTransaction.RecoveryStatus.RECOVERY_REQUIRED,
                failure.status());
        assertTrue(failure.recoveryArtifacts().contains(journalOf(primary)));
        assertTrue(failure.recoveryArtifacts().stream().allMatch(Files::exists));
        assertTrue(Files.exists(primary));
        assertArrayEquals(oldBackup, Files.readAllBytes(backup));
        BaselineFileTransaction.RecoveryResult completed =
                new BaselineFileTransaction().recover(primary);
        assertEquals(BaselineFileTransaction.RecoveryOutcome.ROLLED_BACK,
                completed.outcome());
        assertTrue(completed.cleanupWarnings().isEmpty());
        assertArrayEquals(oldPrimary, Files.readAllBytes(primary));
        assertFalse(Files.exists(journalOf(primary)));
        assertNoTransactionFiles(primary.getParent());
    }

    @Test
    void ambiguousRecoveryDoesNotOverwriteAnUnexpectedValidBaseline() throws Exception {
        Path monitored = createMonitoredDirectory("ambiguous-recovery");
        Path primary = temporaryDirectory.resolve("ambiguous-recovery.txt");
        Path backup = backupOf(primary);
        Files.writeString(monitored.resolve("trusted.txt"), "trusted");
        Files.write(primary, "previous primary".getBytes(StandardCharsets.UTF_8));
        Files.write(backup, "previous backup".getBytes(StandardCharsets.UTF_8));
        BaselineSnapshot snapshot = new BaselineManager().prepareBaseline(monitored);
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.crashAfterMove(primary, 1);
        assertThrows(SimulatedCrash.class,
                () -> new BaselineFileTransaction(operations).commit(snapshot, primary));

        Path externalBaseline = temporaryDirectory.resolve("external-baseline.txt");
        BaselineManager externalManager = new BaselineManager();
        externalManager.createBaseline(monitored);
        externalManager.saveBaselineToFile(externalBaseline);
        byte[] unexpectedValidBackup = Files.readAllBytes(externalBaseline);
        Files.write(backup, unexpectedValidBackup);
        byte[] interruptedPrimary = Files.readAllBytes(primary);

        BaselineFileTransaction.RecoveryException failure = assertThrows(
                BaselineFileTransaction.RecoveryException.class,
                () -> new BaselineFileTransaction().recover(primary));

        assertTrue(failure.recoveryFailure().getCause().getMessage()
                .contains("unknown contents"));
        assertArrayEquals(interruptedPrimary, Files.readAllBytes(primary));
        assertArrayEquals(unexpectedValidBackup, Files.readAllBytes(backup));
        assertTrue(Files.exists(journalOf(primary)));
        assertFalse(failure.recoveryArtifacts().isEmpty());
    }

    @Test
    void concurrentTransactionsAreSerializedByTheInternalLock() throws Exception {
        Path monitored = createMonitoredDirectory("concurrent");
        Path primary = temporaryDirectory.resolve("concurrent-baseline.txt");
        BaselineSnapshot first = writeSnapshotFile(monitored, "first.txt", "first");
        BaselineSnapshot second = writeSnapshotFile(monitored, "second.txt", "second");
        BlockingFileOperations operations = new BlockingFileOperations();
        BaselineFileTransaction firstTransaction = new BaselineFileTransaction(operations);
        BaselineFileTransaction secondTransaction = new BaselineFileTransaction(operations);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var firstCommit = executor.submit(() -> firstTransaction.commit(first, primary));
            assertTrue(operations.firstMoveEntered.await(5, TimeUnit.SECONDS));
            var secondStarted = new CountDownLatch(1);
            var secondCommit = executor.submit(() -> {
                secondStarted.countDown();
                return secondTransaction.commit(second, primary);
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertFalse(operations.overlappingMove.await(150, TimeUnit.MILLISECONDS));
            operations.releaseFirstMove.countDown();
            firstCommit.get(5, TimeUnit.SECONDS);
            secondCommit.get(5, TimeUnit.SECONDS);

            assertEquals(1, operations.maximumConcurrentMoves.get());
            BaselineManager loaded = new BaselineManager();
            loaded.loadBaselineFromFile(primary, monitored);
            assertEquals(second.entries(), loaded.getBaselineEntries());
            BaselineManager loadedBackup = new BaselineManager();
            loadedBackup.loadBaselineFromFile(backupOf(primary), monitored);
            assertEquals(first.entries(), loadedBackup.getBaselineEntries());
        } finally {
            operations.releaseFirstMove.countDown();
            executor.shutdownNow();
        }
    }

    private void assertMissingCombination(
            String name, boolean createPrimary, boolean createBackup) throws Exception {
        Path monitored = createMonitoredDirectory("combination-" + name);
        Path primary = temporaryDirectory.resolve("combination-" + name + ".txt");
        Path backup = backupOf(primary);
        byte[] oldPrimary = ("primary " + name).getBytes(StandardCharsets.UTF_8);
        byte[] oldBackup = ("backup " + name).getBytes(StandardCharsets.UTF_8);
        if (createPrimary) {
            Files.write(primary, oldPrimary);
        }
        if (createBackup) {
            Files.write(backup, oldBackup);
        }
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");

        new BaselineFileTransaction().commit(snapshot, primary);

        assertTrue(Files.exists(primary));
        if (createPrimary) {
            assertArrayEquals(oldPrimary, Files.readAllBytes(backup));
        } else if (createBackup) {
            assertArrayEquals(oldBackup, Files.readAllBytes(backup));
        } else {
            assertFalse(Files.exists(backup));
        }
    }

    private void recoverMissingCombination(
            String name, boolean createPrimary, boolean createBackup) throws Exception {
        Path monitored = createMonitoredDirectory("recover-combination-" + name);
        Path primary = temporaryDirectory.resolve("recover-combination-" + name + ".txt");
        Path backup = backupOf(primary);
        byte[] oldPrimary = ("primary " + name).getBytes(StandardCharsets.UTF_8);
        byte[] oldBackup = ("backup " + name).getBytes(StandardCharsets.UTF_8);
        if (createPrimary) {
            Files.write(primary, oldPrimary);
        }
        if (createBackup) {
            Files.write(backup, oldBackup);
        }
        BaselineSnapshot snapshot = writeSnapshotFile(monitored, "new.txt", "candidate");
        FaultInjectingFileOperations operations = new FaultInjectingFileOperations();
        operations.crashAfterMove(primary, 1);

        assertThrows(SimulatedCrash.class,
                () -> new BaselineFileTransaction(operations).commit(snapshot, primary));
        new BaselineFileTransaction().recover(primary);

        assertEquals(createPrimary, Files.exists(primary));
        assertEquals(createBackup, Files.exists(backup));
        if (createPrimary) {
            assertArrayEquals(oldPrimary, Files.readAllBytes(primary));
        }
        if (createBackup) {
            assertArrayEquals(oldBackup, Files.readAllBytes(backup));
        }
    }

    private Path createMonitoredDirectory(String name) throws IOException {
        return Files.createDirectory(temporaryDirectory.resolve(name));
    }

    private BaselineSnapshot writeSnapshotFile(Path monitored, String name, String contents)
            throws IOException {
        Files.writeString(monitored.resolve(name), contents);
        return new BaselineManager().prepareBaseline(monitored);
    }

    private Path backupOf(Path primary) {
        return primary.resolveSibling(primary.getFileName() + BaselineManager.BACKUP_SUFFIX);
    }

    private Path journalOf(Path primary) {
        return primary.resolveSibling(
                primary.getFileName() + ".fim-transaction.journal");
    }

    private void assertNoTransactionFiles(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path ->
                    path.getFileName().toString().contains(".fim-txn-")));
        }
    }

    private boolean hasBytes(Path path, byte[] expected) {
        try {
            return Arrays.equals(expected, Files.readAllBytes(path));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static class FaultInjectingFileOperations
            implements BaselineFileTransaction.FileOperations {

        private final Map<Path, Integer> failedMoveOccurrences = new HashMap<>();
        private final Map<Path, Integer> failedAfterMoveOccurrences = new HashMap<>();
        private final Map<Path, Integer> crashAfterMoveOccurrences = new HashMap<>();
        private final Map<Path, Integer> moveCounts = new HashMap<>();
        private boolean failWrite;
        private String failDeleteSuffix;
        private Path watchedBackup;
        private byte[] expectedBackup;
        private boolean backupMatchedAtPrimaryMove;

        private void failMove(Path target, int occurrence) {
            failedMoveOccurrences.put(target.toAbsolutePath().normalize(), occurrence);
        }

        private void failAfterMove(Path target, int occurrence) {
            failedAfterMoveOccurrences.put(target.toAbsolutePath().normalize(), occurrence);
        }

        private void crashAfterMove(Path target, int occurrence) {
            crashAfterMoveOccurrences.put(target.toAbsolutePath().normalize(), occurrence);
        }

        private void watchBackupDuringPrimaryMove(Path backup, byte[] expected) {
            watchedBackup = backup;
            expectedBackup = expected;
        }

        @Override
        public void createDirectories(Path directory) throws IOException {
            Files.createDirectories(directory);
        }

        @Override
        public boolean exists(Path path) {
            return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public boolean isRegularFile(Path path) {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public Path createTempFile(Path directory, String prefix, String suffix)
                throws IOException {
            return Files.createTempFile(directory, prefix, suffix);
        }

        @Override
        public void write(Path path, byte[] content) throws IOException {
            if (failWrite) {
                throw new IOException("Injected staging write failure");
            }
            Files.write(path, content,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        @Override
        public byte[] read(Path path) throws IOException {
            return Files.readAllBytes(path);
        }

        @Override
        public String sha256(Path path) throws IOException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, count);
                    }
                }
                return java.util.HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public java.util.List<Path> listFiles(Path directory, String filenamePrefix)
                throws IOException {
            try (Stream<Path> paths = Files.list(directory)) {
                return paths.filter(path -> path.getFileName().toString()
                                .startsWith(filenamePrefix))
                        .toList();
            }
        }

        @Override
        public void copy(Path source, Path target) throws IOException {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            Path normalizedTarget = target.toAbsolutePath().normalize();
            int occurrence = moveCounts.merge(normalizedTarget, 1, Integer::sum);
            if (watchedBackup != null
                    && normalizedTarget.equals(primarySibling(watchedBackup))
                    && occurrence == 1) {
                backupMatchedAtPrimaryMove = Arrays.equals(
                        expectedBackup, Files.readAllBytes(watchedBackup));
            }
            if (failedMoveOccurrences.getOrDefault(normalizedTarget, -1) == occurrence) {
                throw new IOException("Injected move failure for " + target);
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            if (failedAfterMoveOccurrences.getOrDefault(normalizedTarget, -1) == occurrence) {
                throw new IOException("Injected post-move failure for " + target);
            }
            if (crashAfterMoveOccurrences.getOrDefault(normalizedTarget, -1) == occurrence) {
                crashAfterMoveOccurrences.remove(normalizedTarget);
                throw new SimulatedCrash();
            }
        }

        @Override
        public void deleteIfExists(Path path) throws IOException {
            if (failDeleteSuffix != null
                    && path.getFileName().toString().endsWith(failDeleteSuffix)) {
                throw new IOException("Injected cleanup failure for " + path);
            }
            Files.deleteIfExists(path);
        }

        private Path primarySibling(Path backup) {
            String name = backup.getFileName().toString();
            return backup.resolveSibling(name.substring(
                    0, name.length() - BaselineManager.BACKUP_SUFFIX.length()));
        }
    }

    private static final class BlockingFileOperations extends FaultInjectingFileOperations {

        private final CountDownLatch firstMoveEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstMove = new CountDownLatch(1);
        private final CountDownLatch overlappingMove = new CountDownLatch(1);
        private final AtomicBoolean firstMove = new AtomicBoolean(true);
        private final AtomicInteger activeMoves = new AtomicInteger();
        private final AtomicInteger maximumConcurrentMoves = new AtomicInteger();

        @Override
        public void move(Path source, Path target) throws IOException {
            int active = activeMoves.incrementAndGet();
            maximumConcurrentMoves.accumulateAndGet(active, Math::max);
            if (active > 1) {
                overlappingMove.countDown();
            }
            try {
                if (firstMove.compareAndSet(true, false)) {
                    firstMoveEntered.countDown();
                    try {
                        if (!releaseFirstMove.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting to release first move");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during concurrency test", exception);
                    }
                }
                super.move(source, target);
            } finally {
                activeMoves.decrementAndGet();
            }
        }
    }

    private static final class SimulatedCrash extends Error {
    }
}
