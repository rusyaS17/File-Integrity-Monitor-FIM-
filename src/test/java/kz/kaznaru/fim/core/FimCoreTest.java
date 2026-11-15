package kz.kaznaru.fim.core;

import kz.kaznaru.fim.model.FileStatus;
import kz.kaznaru.fim.model.ScanReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FimCoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void calculatesSha256Hash() throws Exception {
        Path file = temporaryDirectory.resolve("hello.txt");
        Files.writeString(file, "hello");

        String hash = new HashCalculator().calculateSHA256(file);

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void savesAndLoadsBaseline() throws Exception {
        Path folder = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Path file = folder.resolve("sample.txt");
        Files.writeString(file, "original");
        Path baselineFile = temporaryDirectory.resolve("baseline.txt");

        BaselineManager first = new BaselineManager();
        first.createBaseline(folder);
        first.saveBaselineToFile(baselineFile);

        BaselineManager second = new BaselineManager();
        second.loadBaselineFromFile(baselineFile);

        assertEquals(first.getBaselineEntries(), second.getBaselineEntries());
    }

    @Test
    void detectsSafeModifiedNewAndDeletedFiles() throws Exception {
        Path folder = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Path safeFile = folder.resolve("safe.txt");
        Path modifiedFile = folder.resolve("modified.txt");
        Path deletedFile = folder.resolve("deleted.txt");
        Files.writeString(safeFile, "same");
        Files.writeString(modifiedFile, "before");
        Files.writeString(deletedFile, "will be deleted");

        BaselineManager baseline = new BaselineManager();
        baseline.createBaseline(folder);

        Files.writeString(modifiedFile, "after");
        Files.delete(deletedFile);
        Files.writeString(folder.resolve("new.txt"), "new file");

        ScanReport report = new DirectoryScanner().scan(folder, baseline.getBaselineEntries());

        assertEquals(1, report.count(FileStatus.SAFE));
        assertEquals(1, report.count(FileStatus.MODIFIED));
        assertEquals(1, report.count(FileStatus.NEW));
        assertEquals(1, report.count(FileStatus.DELETED));
        assertEquals(4, report.totalFiles());
    }

    @Test
    void doesNotChangeBaselineDuringScan() throws Exception {
        Path folder = Files.createDirectory(temporaryDirectory.resolve("monitored"));
        Path file = folder.resolve("sample.txt");
        Files.writeString(file, "before");
        BaselineManager baseline = new BaselineManager();
        baseline.createBaseline(folder);
        String originalHash = baseline.getBaselineEntries().get(file.toAbsolutePath().normalize().toString());

        Files.writeString(file, "after");
        new DirectoryScanner().scan(folder, baseline.getBaselineEntries());

        assertEquals(originalHash, baseline.getBaselineEntries().get(file.toAbsolutePath().normalize().toString()));
    }
}
