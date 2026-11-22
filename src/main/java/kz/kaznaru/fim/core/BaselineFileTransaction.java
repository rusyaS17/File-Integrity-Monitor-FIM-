package kz.kaznaru.fim.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Persists immutable snapshots with a same-directory journal for restart recovery.
 */
public final class BaselineFileTransaction {

    private static final String JOURNAL_SUFFIX = ".fim-transaction.journal";
    private static final String JOURNAL_HEADER = "#FIM_BASELINE_TRANSACTION_V1\n";
    private static final String TRANSACTION_TEMP_MARKER = ".fim-txn-";
    private static final ReentrantLock TRANSACTION_LOCK = new ReentrantLock(true);

    public enum Phase {
        STAGING,
        PRIMARY_REPLACEMENT,
        BACKUP_REPLACEMENT
    }

    public enum RecoveryStatus {
        NONE,
        RECOVERY_REQUIRED,
        COMMIT_FINALIZATION_REQUIRED,
        ROLLBACK_FINALIZATION_REQUIRED,
        CORRUPT_JOURNAL,
        ORPHANED_ARTIFACTS
    }

    public enum RecoveryOutcome {
        NONE,
        ROLLED_BACK,
        COMMIT_FINALIZED
    }

    public record CleanupWarning(Path path, String message) {
    }

    public record CommitResult(
            Path primaryFile,
            Path backupFile,
            Path journalFile,
            List<CleanupWarning> cleanupWarnings) {

        public CommitResult {
            cleanupWarnings = List.copyOf(cleanupWarnings);
        }

        public boolean committed() {
            return true;
        }
    }

    public record RecoveryInspection(
            RecoveryStatus status,
            Path journalFile,
            List<Path> artifacts,
            String detail) {

        public RecoveryInspection {
            artifacts = List.copyOf(artifacts);
        }
    }

    public record RecoveryResult(
            RecoveryOutcome outcome,
            Path journalFile,
            List<CleanupWarning> cleanupWarnings) {

        public RecoveryResult {
            cleanupWarnings = List.copyOf(cleanupWarnings);
        }
    }

    public static final class TransactionException extends IOException {

        private final Phase phase;
        private final IOException operationFailure;
        private final IOException rollbackFailure;
        private final List<Path> recoveryArtifacts;
        private final List<CleanupWarning> cleanupWarnings;

        private TransactionException(
                Phase phase,
                IOException operationFailure,
                IOException rollbackFailure,
                List<Path> recoveryArtifacts,
                List<CleanupWarning> cleanupWarnings) {
            super("Baseline transaction failed during " + phase, operationFailure);
            this.phase = phase;
            this.operationFailure = operationFailure;
            this.rollbackFailure = rollbackFailure;
            this.recoveryArtifacts = List.copyOf(recoveryArtifacts);
            this.cleanupWarnings = List.copyOf(cleanupWarnings);
            if (rollbackFailure != null) {
                addSuppressed(rollbackFailure);
            }
        }

        public Phase phase() {
            return phase;
        }

        public IOException operationFailure() {
            return operationFailure;
        }

        public IOException rollbackFailure() {
            return rollbackFailure;
        }

        public List<Path> recoveryArtifacts() {
            return recoveryArtifacts;
        }

        public List<CleanupWarning> cleanupWarnings() {
            return cleanupWarnings;
        }
    }

    public static final class RecoveryException extends IOException {

        private final RecoveryStatus status;
        private final Path journalFile;
        private final List<Path> recoveryArtifacts;
        private final IOException recoveryFailure;

        private RecoveryException(
                RecoveryStatus status,
                Path journalFile,
                IOException recoveryFailure,
                List<Path> recoveryArtifacts) {
            super("Baseline recovery requires attention (" + status + "): "
                    + recoveryFailure.getMessage(), recoveryFailure);
            this.status = status;
            this.journalFile = journalFile;
            this.recoveryFailure = recoveryFailure;
            this.recoveryArtifacts = List.copyOf(recoveryArtifacts);
        }

        public RecoveryStatus status() {
            return status;
        }

        public Path journalFile() {
            return journalFile;
        }

        public IOException recoveryFailure() {
            return recoveryFailure;
        }

        public List<Path> recoveryArtifacts() {
            return recoveryArtifacts;
        }
    }

    interface FileOperations {
        void createDirectories(Path directory) throws IOException;

        boolean exists(Path path) throws IOException;

        boolean isRegularFile(Path path) throws IOException;

        Path createTempFile(Path directory, String prefix, String suffix) throws IOException;

        void write(Path path, byte[] content) throws IOException;

        byte[] read(Path path) throws IOException;

        String sha256(Path path) throws IOException;

        List<Path> listFiles(Path directory, String filenamePrefix) throws IOException;

        void copy(Path source, Path target) throws IOException;

        void move(Path source, Path target) throws IOException;

        void deleteIfExists(Path path) throws IOException;
    }

    private enum JournalState {
        PREPARED,
        PRIMARY_REPLACED,
        COMMITTED,
        ROLLED_BACK
    }

    private record JournalRecord(
            JournalState state,
            Path primary,
            Path backup,
            boolean hadPrimary,
            boolean hadBackup,
            Path candidate,
            Path previousPrimary,
            Path previousBackup,
            Path replacementBackup,
            String candidateHash,
            String primaryHash,
            String backupHash) {

        private JournalRecord withState(JournalState nextState) {
            return new JournalRecord(
                    nextState, primary, backup, hadPrimary, hadBackup,
                    candidate, previousPrimary, previousBackup, replacementBackup,
                    candidateHash, primaryHash, backupHash);
        }
    }

    private final FileOperations fileOperations;

    public BaselineFileTransaction() {
        this(new NioFileOperations());
    }

    BaselineFileTransaction(FileOperations fileOperations) {
        this.fileOperations = Objects.requireNonNull(fileOperations, "fileOperations");
    }

    /**
     * Reports whether startup should route to explicit recovery before another commit.
     */
    public RecoveryInspection inspectRecovery(Path baselineFile) throws RecoveryException {
        Objects.requireNonNull(baselineFile, "baselineFile");
        TRANSACTION_LOCK.lock();
        try {
            Path primary = normalizeBaselinePath(baselineFile);
            Path journal = journalPath(primary);
            List<Path> artifacts = listArtifacts(primary, journal, null);
            if (!fileOperations.exists(journal)) {
                RecoveryStatus status = artifacts.isEmpty()
                        ? RecoveryStatus.NONE
                        : RecoveryStatus.ORPHANED_ARTIFACTS;
                return new RecoveryInspection(status, journal, artifacts,
                        status == RecoveryStatus.NONE
                                ? "No unfinished baseline transaction."
                                : "Transaction artifacts exist without a readable journal; "
                                        + "preserve them for manual recovery.");
            }
            try {
                JournalRecord record = readJournal(journal, primary);
                RecoveryStatus status = switch (record.state()) {
                    case PREPARED, PRIMARY_REPLACED -> RecoveryStatus.RECOVERY_REQUIRED;
                    case COMMITTED -> RecoveryStatus.COMMIT_FINALIZATION_REQUIRED;
                    case ROLLED_BACK -> RecoveryStatus.ROLLBACK_FINALIZATION_REQUIRED;
                };
                return new RecoveryInspection(status, journal, artifacts,
                        "Explicit recovery is required before another baseline transaction.");
            } catch (IOException exception) {
                return new RecoveryInspection(RecoveryStatus.CORRUPT_JOURNAL,
                        journal, artifacts, exception.getMessage());
            }
        } catch (IOException exception) {
            if (exception instanceof RecoveryException recoveryFailure) {
                throw recoveryFailure;
            }
            throw recoveryException(
                    RecoveryStatus.ORPHANED_ARTIFACTS,
                    journalPath(normalizeBaselinePath(baselineFile)),
                    exception,
                    List.of());
        } finally {
            TRANSACTION_LOCK.unlock();
        }
    }

    public boolean hasUnfinishedTransaction(Path baselineFile) throws RecoveryException {
        return inspectRecovery(baselineFile).status() != RecoveryStatus.NONE;
    }

    public CommitResult commit(BaselineSnapshot snapshot, Path baselineFile)
            throws TransactionException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(baselineFile, "baselineFile");

        TRANSACTION_LOCK.lock();
        try {
            return commitLocked(snapshot, normalizeBaselinePath(baselineFile));
        } finally {
            TRANSACTION_LOCK.unlock();
        }
    }

    /**
     * Explicitly restores the pre-transaction files or finalizes an already completed operation.
     */
    public RecoveryResult recover(Path baselineFile) throws RecoveryException {
        Objects.requireNonNull(baselineFile, "baselineFile");
        TRANSACTION_LOCK.lock();
        try {
            return recoverLocked(normalizeBaselinePath(baselineFile));
        } finally {
            TRANSACTION_LOCK.unlock();
        }
    }

    private CommitResult commitLocked(BaselineSnapshot snapshot, Path primary)
            throws TransactionException {
        Path directory = primary.getParent();
        if (directory == null || primary.getFileName() == null) {
            throw new IllegalArgumentException("Baseline file must have a parent directory");
        }
        Path backup = backupPath(primary);
        Path journal = journalPath(primary);
        List<Path> stagedFiles = new ArrayList<>();
        Phase phase = Phase.STAGING;

        try {
            fileOperations.createDirectories(directory);
            List<Path> oldArtifacts = listArtifacts(primary, journal, null);
            if (fileOperations.exists(journal) || !oldArtifacts.isEmpty()) {
                throw new IOException("An unfinished baseline transaction already exists. "
                        + "Inspect and explicitly recover it before committing.");
            }

            boolean hadPrimary = fileOperations.exists(primary);
            boolean hadBackup = fileOperations.exists(backup);
            requireRegularFileIfPresent(primary, hadPrimary);
            requireRegularFileIfPresent(backup, hadBackup);
            String primaryHash = hadPrimary ? fileOperations.sha256(primary) : "-";
            String backupHash = hadBackup ? fileOperations.sha256(backup) : "-";

            Path candidate = createStagedFile(
                    stagedFiles, directory, primary, ".candidate");
            fileOperations.write(candidate, BaselineManager.serializeSnapshot(snapshot));
            String candidateHash = fileOperations.sha256(candidate);

            Path previousPrimary = null;
            Path replacementBackup = null;
            Path previousBackup = null;
            if (hadPrimary) {
                previousPrimary = createStagedFile(
                        stagedFiles, directory, primary, ".previous-primary");
                fileOperations.copy(primary, previousPrimary);
                requireSameHash(previousPrimary, primaryHash, "staged primary recovery copy");
                replacementBackup = createStagedFile(
                        stagedFiles, directory, primary, ".replacement-backup");
                fileOperations.copy(primary, replacementBackup);
                requireSameHash(replacementBackup, primaryHash, "staged backup replacement");
            }
            if (hadBackup) {
                previousBackup = createStagedFile(
                        stagedFiles, directory, primary, ".previous-backup");
                fileOperations.copy(backup, previousBackup);
                requireSameHash(previousBackup, backupHash, "staged backup recovery copy");
            }

            JournalRecord record = new JournalRecord(
                    JournalState.PREPARED,
                    primary,
                    backup,
                    hadPrimary,
                    hadBackup,
                    candidate,
                    previousPrimary,
                    previousBackup,
                    replacementBackup,
                    candidateHash,
                    primaryHash,
                    backupHash);
            writeJournal(record, stagedFiles);

            phase = Phase.PRIMARY_REPLACEMENT;
            fileOperations.move(candidate, primary);
            record = record.withState(JournalState.PRIMARY_REPLACED);
            writeJournal(record, stagedFiles);

            phase = Phase.BACKUP_REPLACEMENT;
            if (hadPrimary) {
                fileOperations.move(replacementBackup, backup);
            }
            record = record.withState(JournalState.COMMITTED);
            writeJournal(record, stagedFiles);

            List<CleanupWarning> warnings = cleanup(stagedFiles);
            if (warnings.isEmpty()) {
                try {
                    fileOperations.deleteIfExists(journal);
                } catch (IOException exception) {
                    warnings = List.of(new CleanupWarning(journal, failureMessage(exception)));
                }
            }
            return new CommitResult(primary, backup, journal, warnings);
        } catch (IOException operationFailure) {
            boolean journalExists;
            try {
                journalExists = fileOperations.exists(journal);
            } catch (IOException stateFailure) {
                throw transactionException(
                        phase, operationFailure, stateFailure,
                        primary, journal, stagedFiles, List.of());
            }
            if (!journalExists) {
                List<CleanupWarning> warnings = cleanup(stagedFiles);
                throw transactionException(
                        phase, operationFailure, null,
                        primary, journal, stagedFiles, warnings);
            }

            IOException rollbackFailure = null;
            List<CleanupWarning> warnings = List.of();
            try {
                RecoveryResult recovery = recoverLocked(primary);
                warnings = recovery.cleanupWarnings();
                if (recovery.outcome() == RecoveryOutcome.COMMIT_FINALIZED) {
                    List<CleanupWarning> committedWarnings = new ArrayList<>(warnings);
                    committedWarnings.add(new CleanupWarning(
                            journal, "The commit was verified complete after an I/O error."));
                    return new CommitResult(primary, backup, journal, committedWarnings);
                }
            } catch (RecoveryException exception) {
                rollbackFailure = exception;
            }
            throw transactionException(
                    phase, operationFailure, rollbackFailure,
                    primary, journal, stagedFiles, warnings);
        }
    }

    private RecoveryResult recoverLocked(Path primary) throws RecoveryException {
        Path journal = journalPath(primary);
        List<Path> artifacts;
        try {
            artifacts = listArtifacts(primary, journal, null);
            if (!fileOperations.exists(journal)) {
                if (!artifacts.isEmpty()) {
                    throw recoveryException(
                            RecoveryStatus.ORPHANED_ARTIFACTS,
                            journal,
                            new IOException("Transaction artifacts exist without a journal. "
                                    + "Do not delete them; inspect the baseline directory."),
                            artifacts);
                }
                return new RecoveryResult(RecoveryOutcome.NONE, journal, List.of());
            }
        } catch (RecoveryException exception) {
            throw exception;
        } catch (IOException exception) {
            throw recoveryException(
                    RecoveryStatus.ORPHANED_ARTIFACTS, journal, exception, List.of(journal));
        }

        JournalRecord record;
        try {
            record = readJournal(journal, primary);
        } catch (IOException exception) {
            throw recoveryException(
                    RecoveryStatus.CORRUPT_JOURNAL,
                    journal,
                    new IOException("Journal is corrupt or inconsistent. Preserve all listed "
                            + "artifacts and repair the journal before retrying.", exception),
                    artifacts);
        }

        List<Path> recoveryTemps = new ArrayList<>();
        try {
            RecoveryOutcome outcome;
            if (record.state() == JournalState.COMMITTED) {
                validateCommittedFiles(record);
                outcome = RecoveryOutcome.COMMIT_FINALIZED;
            } else if (record.state() == JournalState.ROLLED_BACK) {
                validateOriginalFiles(record);
                outcome = RecoveryOutcome.ROLLED_BACK;
            } else {
                validateInFlightFiles(record);
                validateRecoveryCopies(record);
                restoreOriginalFiles(record, recoveryTemps);
                validateOriginalFiles(record);
                record = record.withState(JournalState.ROLLED_BACK);
                writeJournal(record, recoveryTemps);
                outcome = RecoveryOutcome.ROLLED_BACK;
            }

            List<CleanupWarning> warnings =
                    cleanupTransactionArtifacts(record, journal, recoveryTemps);
            return new RecoveryResult(outcome, journal, warnings);
        } catch (IOException exception) {
            if (exception instanceof RecoveryException recoveryFailure) {
                throw recoveryFailure;
            }
            artifacts = listArtifacts(primary, journal, recoveryTemps);
            throw recoveryException(
                    RecoveryStatus.RECOVERY_REQUIRED,
                    journal,
                    new IOException("Recovery did not complete. Preserve all recovery artifacts "
                            + "and retry after resolving the reported I/O error.", exception),
                    artifacts);
        }
    }

    private void validateInFlightFiles(JournalRecord record) throws IOException {
        String primaryHash = hashIfRegularOrAbsent(record.primary());
        Set<String> allowedPrimary = new LinkedHashSet<>();
        allowedPrimary.add(record.candidateHash());
        if (record.hadPrimary()) {
            allowedPrimary.add(record.primaryHash());
        } else {
            allowedPrimary.add(null);
        }
        if (!allowedPrimary.contains(primaryHash)) {
            throw new IOException("Primary baseline has unknown contents; refusing to overwrite "
                    + "it during recovery: " + record.primary());
        }

        String backupHash = hashIfRegularOrAbsent(record.backup());
        Set<String> allowedBackup = new LinkedHashSet<>();
        if (record.hadBackup()) {
            allowedBackup.add(record.backupHash());
        } else {
            allowedBackup.add(null);
        }
        if (record.hadPrimary()) {
            allowedBackup.add(record.primaryHash());
        }
        if (!allowedBackup.contains(backupHash)) {
            throw new IOException("Backup baseline has unknown contents; refusing to overwrite "
                    + "it during recovery: " + record.backup());
        }
    }

    private void validateRecoveryCopies(JournalRecord record) throws IOException {
        if (record.hadPrimary()) {
            requireSameHash(record.previousPrimary(), record.primaryHash(),
                    "primary recovery copy");
            if (fileOperations.exists(record.replacementBackup())) {
                requireSameHash(record.replacementBackup(), record.primaryHash(),
                        "backup replacement copy");
            } else if (!record.primaryHash().equals(
                    hashIfRegularOrAbsent(record.backup()))) {
                throw new IOException("Missing backup replacement copy and installed backup "
                        + "does not match the previous primary");
            }
        }
        if (record.hadBackup()) {
            requireSameHash(record.previousBackup(), record.backupHash(),
                    "backup recovery copy");
        }
        if (fileOperations.exists(record.candidate())) {
            requireSameHash(record.candidate(), record.candidateHash(),
                    "candidate baseline");
        }
    }

    private void restoreOriginalFiles(JournalRecord record, List<Path> recoveryTemps)
            throws IOException {
        List<IOException> failures = new ArrayList<>();
        try {
            restore(record.primary(), record.hadPrimary(), record.previousPrimary(),
                    record, ".restore-primary", recoveryTemps);
        } catch (IOException exception) {
            failures.add(exception);
        }
        try {
            restore(record.backup(), record.hadBackup(), record.previousBackup(),
                    record, ".restore-backup", recoveryTemps);
        } catch (IOException exception) {
            failures.add(exception);
        }
        IOException combined = combineFailures("Could not restore all baseline files", failures);
        if (combined != null) {
            throw combined;
        }
    }

    private void restore(
            Path target,
            boolean existed,
            Path savedCopy,
            JournalRecord record,
            String suffix,
            List<Path> recoveryTemps) throws IOException {
        if (!existed) {
            fileOperations.deleteIfExists(target);
            return;
        }
        if (savedCopy == null) {
            throw new IOException("Missing recovery copy for " + target);
        }
        requireSameHash(savedCopy,
                target.equals(record.primary()) ? record.primaryHash() : record.backupHash(),
                "recovery source");
        Path restoreFile = createStagedFile(
                recoveryTemps, record.primary().getParent(), record.primary(), suffix);
        fileOperations.copy(savedCopy, restoreFile);
        fileOperations.move(restoreFile, target);
    }

    private void validateOriginalFiles(JournalRecord record) throws IOException {
        validateFileState(record.primary(), record.hadPrimary(), record.primaryHash());
        validateFileState(record.backup(), record.hadBackup(), record.backupHash());
    }

    private void validateCommittedFiles(JournalRecord record) throws IOException {
        requireSameHash(record.primary(), record.candidateHash(), "committed primary");
        if (record.hadPrimary()) {
            requireSameHash(record.backup(), record.primaryHash(), "committed backup");
        } else {
            validateFileState(record.backup(), record.hadBackup(), record.backupHash());
        }
    }

    private void validateFileState(Path path, boolean existed, String expectedHash)
            throws IOException {
        String actualHash = hashIfRegularOrAbsent(path);
        if (existed) {
            if (!expectedHash.equals(actualHash)) {
                throw new IOException("Baseline file does not match its journaled original: "
                        + path);
            }
        } else if (actualHash != null) {
            throw new IOException("A baseline file that was originally absent is present: "
                    + path);
        }
    }

    private String hashIfRegularOrAbsent(Path path) throws IOException {
        if (!fileOperations.exists(path)) {
            return null;
        }
        if (!fileOperations.isRegularFile(path)) {
            throw new IOException("Baseline transaction path is not a regular file: " + path);
        }
        return fileOperations.sha256(path);
    }

    private void requireSameHash(Path path, String expectedHash, String description)
            throws IOException {
        if (path == null || !fileOperations.isRegularFile(path)) {
            throw new IOException("Missing " + description + ": " + path);
        }
        String actualHash = fileOperations.sha256(path);
        if (!expectedHash.equals(actualHash)) {
            throw new IOException("Hash mismatch for " + description + ": " + path);
        }
    }

    private List<CleanupWarning> cleanupTransactionArtifacts(
            JournalRecord record, Path journal, List<Path> temporaryFiles) throws IOException {
        LinkedHashSet<Path> cleanupTargets = new LinkedHashSet<>(temporaryFiles);
        cleanupTargets.addAll(recordStageFiles(record));

        List<Path> transactionFiles = listTransactionFiles(record.primary());
        List<CleanupWarning> warnings = new ArrayList<>();
        for (Path transactionFile : transactionFiles) {
            if (cleanupTargets.contains(transactionFile)) {
                continue;
            }
            String name = transactionFile.getFileName().toString();
            if (name.endsWith(".restore-primary")
                    && record.hadPrimary()
                    && hasExpectedHash(transactionFile, record.primaryHash())) {
                cleanupTargets.add(transactionFile);
            } else if (name.endsWith(".restore-backup")
                    && record.hadBackup()
                    && hasExpectedHash(transactionFile, record.backupHash())) {
                cleanupTargets.add(transactionFile);
            } else if (name.endsWith(".journal-update")
                    && isValidJournalUpdate(transactionFile, record.primary())) {
                cleanupTargets.add(transactionFile);
            } else {
                warnings.add(new CleanupWarning(transactionFile,
                        "Unrecognized transaction artifact was preserved for inspection."));
            }
        }
        warnings.addAll(cleanup(new ArrayList<>(cleanupTargets)));
        if (warnings.isEmpty()) {
            try {
                fileOperations.deleteIfExists(journal);
            } catch (IOException exception) {
                warnings = List.of(new CleanupWarning(journal, failureMessage(exception)));
            }
        }
        return List.copyOf(warnings);
    }

    private boolean hasExpectedHash(Path path, String expectedHash) throws IOException {
        return fileOperations.isRegularFile(path)
                && expectedHash.equals(fileOperations.sha256(path));
    }

    private boolean isValidJournalUpdate(Path path, Path primary) {
        try {
            readJournal(path, primary);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private List<CleanupWarning> cleanup(List<Path> stagedFiles) {
        List<CleanupWarning> warnings = new ArrayList<>();
        for (Path stagedFile : stagedFiles) {
            try {
                fileOperations.deleteIfExists(stagedFile);
            } catch (IOException exception) {
                warnings.add(new CleanupWarning(stagedFile, failureMessage(exception)));
            }
        }
        return List.copyOf(warnings);
    }

    private void writeJournal(JournalRecord record, List<Path> stagedFiles) throws IOException {
        Path journal = journalPath(record.primary());
        Path temporaryJournal = createStagedFile(
                stagedFiles, record.primary().getParent(), record.primary(), ".journal-update");
        fileOperations.write(temporaryJournal, serializeJournal(record));
        fileOperations.move(temporaryJournal, journal);
    }

    private JournalRecord readJournal(Path journal, Path expectedPrimary) throws IOException {
        if (!fileOperations.isRegularFile(journal)) {
            throw new IOException("Transaction journal is not a regular file: " + journal);
        }
        byte[] bytes = fileOperations.read(journal);
        String content = new String(bytes, StandardCharsets.UTF_8);
        int checksumOffset = content.lastIndexOf("checksum=");
        if (!content.startsWith(JOURNAL_HEADER) || checksumOffset < JOURNAL_HEADER.length()
                || !content.endsWith("\n")) {
            throw new IOException("Invalid baseline transaction journal header or terminator");
        }
        String payload = content.substring(0, checksumOffset);
        String checksumLine = content.substring(checksumOffset, content.length() - 1);
        if (!checksumLine.matches("checksum=[0-9a-f]{64}")
                || !sha256(payload.getBytes(StandardCharsets.UTF_8))
                        .equals(checksumLine.substring("checksum=".length()))) {
            throw new IOException("Baseline transaction journal checksum is invalid");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        String[] lines = payload.substring(JOURNAL_HEADER.length()).split("\n");
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0 || fields.putIfAbsent(
                    line.substring(0, separator), line.substring(separator + 1)) != null) {
                throw new IOException("Malformed or duplicate journal field");
            }
        }
        if (!fields.keySet().equals(journalFields())) {
            throw new IOException("Baseline transaction journal fields are incomplete or unknown");
        }

        try {
            JournalState state = JournalState.valueOf(requiredField(fields, "state"));
            Path primary = decodePath(requiredField(fields, "primary"));
            Path backup = decodePath(requiredField(fields, "backup"));
            boolean hadPrimary = parseBoolean(requiredField(fields, "hadPrimary"));
            boolean hadBackup = parseBoolean(requiredField(fields, "hadBackup"));
            Path candidate = decodePath(requiredField(fields, "candidate"));
            Path previousPrimary = decodeOptionalPath(fields.get("previousPrimary"));
            Path previousBackup = decodeOptionalPath(fields.get("previousBackup"));
            Path replacementBackup = decodeOptionalPath(fields.get("replacementBackup"));
            String candidateHash = requiredHash(fields, "candidateHash", false);
            String primaryHash = requiredHash(fields, "primaryHash", !hadPrimary);
            String backupHash = requiredHash(fields, "backupHash", !hadBackup);

            Path expectedBackup = backupPath(expectedPrimary);
            if (!primary.equals(expectedPrimary) || !backup.equals(expectedBackup)) {
                throw new IOException("Journal targets do not match the requested baseline path");
            }
            requireTransactionChild(primary, candidate);
            requireOptionalTransactionChild(primary, previousPrimary);
            requireOptionalTransactionChild(primary, previousBackup);
            requireOptionalTransactionChild(primary, replacementBackup);
            if (hadPrimary != (previousPrimary != null)
                    || hadPrimary != (replacementBackup != null)
                    || hadBackup != (previousBackup != null)
                    || !hadPrimary && !"-".equals(primaryHash)
                    || !hadBackup && !"-".equals(backupHash)
                    || hadPrimary && "-".equals(primaryHash)
                    || hadBackup && "-".equals(backupHash)) {
                throw new IOException("Journal existence flags and recovery paths disagree");
            }
            if (candidate.equals(previousPrimary) || candidate.equals(previousBackup)
                    || candidate.equals(replacementBackup)
                    || previousPrimary != null && previousPrimary.equals(previousBackup)
                    || replacementBackup != null && replacementBackup.equals(previousBackup)) {
                throw new IOException("Journal reuses a staged file path");
            }
            return new JournalRecord(
                    state, primary, backup, hadPrimary, hadBackup,
                    candidate, previousPrimary, previousBackup, replacementBackup,
                    candidateHash, primaryHash, backupHash);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid value in baseline transaction journal", exception);
        }
    }

    private byte[] serializeJournal(JournalRecord record) {
        Map<String, String> fields = new java.util.TreeMap<>();
        fields.put("state", record.state().name());
        fields.put("primary", encodePath(record.primary()));
        fields.put("backup", encodePath(record.backup()));
        fields.put("hadPrimary", Boolean.toString(record.hadPrimary()));
        fields.put("hadBackup", Boolean.toString(record.hadBackup()));
        fields.put("candidate", encodePath(record.candidate()));
        fields.put("previousPrimary", encodeOptionalPath(record.previousPrimary()));
        fields.put("previousBackup", encodeOptionalPath(record.previousBackup()));
        fields.put("replacementBackup", encodeOptionalPath(record.replacementBackup()));
        fields.put("candidateHash", record.candidateHash());
        fields.put("primaryHash", record.primaryHash());
        fields.put("backupHash", record.backupHash());

        StringBuilder payload = new StringBuilder(JOURNAL_HEADER);
        fields.forEach((key, value) -> payload.append(key).append('=')
                .append(value).append('\n'));
        String body = payload.toString();
        return (body + "checksum=" + sha256(body.getBytes(StandardCharsets.UTF_8)) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private Set<String> journalFields() {
        return Set.of(
                "state", "primary", "backup", "hadPrimary", "hadBackup",
                "candidate", "previousPrimary", "previousBackup", "replacementBackup",
                "candidateHash", "primaryHash", "backupHash");
    }

    private String requiredField(Map<String, String> fields, String name) throws IOException {
        String value = fields.get(name);
        if (value == null || value.isEmpty()) {
            throw new IOException("Missing journal field: " + name);
        }
        return value;
    }

    private boolean parseBoolean(String value) throws IOException {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IOException("Invalid journal boolean value: " + value);
        }
        return Boolean.parseBoolean(value);
    }

    private String requiredHash(Map<String, String> fields, String name, boolean optional)
            throws IOException {
        String hash = requiredField(fields, name);
        if ("-".equals(hash) && optional) {
            return hash;
        }
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid SHA-256 value in journal field: " + name);
        }
        return hash;
    }

    private Path decodeOptionalPath(String encoded) throws IOException {
        return "-".equals(encoded) ? null : decodePath(encoded);
    }

    private Path decodePath(String encoded) throws IOException {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            String pathValue = new String(decoded, StandardCharsets.UTF_8);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
                throw new IOException("Non-canonical path encoding in transaction journal");
            }
            Path path = Path.of(pathValue);
            if (!path.isAbsolute() || !path.normalize().equals(path)) {
                throw new IOException("Transaction journal path is not normalized and absolute");
            }
            return path;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid path encoding in transaction journal", exception);
        }
    }

    private void requireTransactionChild(Path primary, Path staged) throws IOException {
        if (staged == null || !isTransactionChild(primary, staged)) {
            throw new IOException("Journal staged path is outside the baseline directory");
        }
    }

    private void requireOptionalTransactionChild(Path primary, Path staged) throws IOException {
        if (staged != null && !isTransactionChild(primary, staged)) {
            throw new IOException("Journal recovery path is outside the baseline directory");
        }
    }

    private boolean isTransactionChild(Path primary, Path staged) {
        return primary.getParent().equals(staged.getParent())
                && staged.getFileName().toString().startsWith(transactionPrefix(primary));
    }

    private String encodeOptionalPath(Path path) {
        return path == null ? "-" : encodePath(path);
    }

    private String encodePath(Path path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                path.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Path normalizeBaselinePath(Path baselineFile) {
        Path primary = baselineFile.toAbsolutePath().normalize();
        if (primary.getParent() == null || primary.getFileName() == null) {
            throw new IllegalArgumentException("Baseline file must have a parent directory");
        }
        return primary;
    }

    private Path backupPath(Path primary) {
        return primary.resolveSibling(
                primary.getFileName() + BaselineManager.BACKUP_SUFFIX);
    }

    private Path journalPath(Path primary) {
        return primary.resolveSibling(primary.getFileName() + JOURNAL_SUFFIX);
    }

    private String transactionPrefix(Path primary) {
        return primary.getFileName() + TRANSACTION_TEMP_MARKER;
    }

    private List<Path> listTransactionFiles(Path primary) throws IOException {
        return fileOperations.listFiles(primary.getParent(), transactionPrefix(primary));
    }

    private List<Path> listArtifacts(
            Path primary, Path journal, List<Path> knownFiles) throws RecoveryException {
        LinkedHashSet<Path> artifacts = new LinkedHashSet<>();
        try {
            if (fileOperations.exists(journal)) {
                artifacts.add(journal);
            }
            if (knownFiles != null) {
                for (Path knownFile : knownFiles) {
                    if (fileOperations.exists(knownFile)) {
                        artifacts.add(knownFile);
                    }
                }
            }
            artifacts.addAll(listTransactionFiles(primary));
        } catch (IOException exception) {
            if (knownFiles != null) {
                knownFiles.forEach(artifacts::add);
            }
            throw recoveryException(
                    RecoveryStatus.ORPHANED_ARTIFACTS, journal, exception,
                    List.copyOf(artifacts));
        }
        return List.copyOf(artifacts);
    }

    private List<Path> recordStageFiles(JournalRecord record) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        paths.add(record.candidate());
        if (record.previousPrimary() != null) {
            paths.add(record.previousPrimary());
        }
        if (record.previousBackup() != null) {
            paths.add(record.previousBackup());
        }
        if (record.replacementBackup() != null) {
            paths.add(record.replacementBackup());
        }
        return List.copyOf(paths);
    }

    private Path createStagedFile(
            List<Path> stagedFiles, Path directory, Path primary, String suffix)
            throws IOException {
        Path staged = fileOperations.createTempFile(
                directory, transactionPrefix(primary), suffix);
        stagedFiles.add(staged);
        return staged;
    }

    private void requireRegularFileIfPresent(Path path, boolean exists) throws IOException {
        if (exists && !fileOperations.isRegularFile(path)) {
            throw new IOException("Baseline transaction path is not a regular file: " + path);
        }
    }

    private TransactionException transactionException(
            Phase phase,
            IOException operationFailure,
            IOException rollbackFailure,
            Path primary,
            Path journal,
            List<Path> stagedFiles,
            List<CleanupWarning> warnings) {
        List<Path> artifacts;
        try {
            artifacts = listArtifacts(primary, journal, stagedFiles);
        } catch (RecoveryException exception) {
            operationFailure.addSuppressed(exception);
            artifacts = exception.recoveryArtifacts();
        }
        return new TransactionException(
                phase, operationFailure, rollbackFailure, artifacts, warnings);
    }

    private RecoveryException recoveryException(
            RecoveryStatus status,
            Path journal,
            IOException failure,
            List<Path> artifacts) {
        return new RecoveryException(status, journal, failure, artifacts);
    }

    private IOException combineFailures(String message, List<IOException> failures) {
        if (failures.isEmpty()) {
            return null;
        }
        IOException combined = new IOException(message, failures.get(0));
        for (int index = 1; index < failures.size(); index++) {
            combined.addSuppressed(failures.get(index));
        }
        return combined;
    }

    private String failureMessage(IOException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return toHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static final class NioFileOperations implements FileOperations {

        @Override
        public void createDirectories(Path directory) throws IOException {
            Files.createDirectories(directory);
        }

        @Override
        public boolean exists(Path path) throws IOException {
            try {
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return true;
            } catch (NoSuchFileException exception) {
                return false;
            }
        }

        @Override
        public boolean isRegularFile(Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isRegularFile();
        }

        @Override
        public Path createTempFile(Path directory, String prefix, String suffix)
                throws IOException {
            return Files.createTempFile(directory, prefix, suffix);
        }

        @Override
        public void write(Path path, byte[] content) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            try (FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
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
                return toHex(digest.digest());
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        private String toHex(byte[] bytes) {
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        }

        @Override
        public List<Path> listFiles(Path directory, String filenamePrefix) throws IOException {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(
                        directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException exception) {
                return List.of();
            }
            if (!attributes.isDirectory()) {
                throw new IOException("Transaction directory is not a directory: " + directory);
            }
            try (Stream<Path> paths = Files.list(directory)) {
                return paths.filter(path -> path.getFileName().toString()
                                .startsWith(filenamePrefix))
                        .toList();
            }
        }

        @Override
        public void copy(Path source, Path target) throws IOException {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            try {
                Files.move(source, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (FileAlreadyExistsException exception) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public void deleteIfExists(Path path) throws IOException {
            Files.deleteIfExists(path);
        }
    }
}
