package kz.kaznaru.fim.console;

import kz.kaznaru.fim.core.BaselineManager;
import kz.kaznaru.fim.core.DirectoryScanner;
import kz.kaznaru.fim.model.FileScanResult;
import kz.kaznaru.fim.model.FileStatus;
import kz.kaznaru.fim.model.ScanReport;

import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    private static final Path MONITORED_FOLDER = Path.of("monitored");
    private static final Path BASELINE_FILE = Path.of("baseline.txt");

    public static void main(String[] args) {
        try {
            Files.createDirectories(MONITORED_FOLDER);
            BaselineManager baselineManager = new BaselineManager();
            String command = args.length == 0 ? "scan" : args[0].toLowerCase();

            System.out.println("========================================");
            System.out.println("       FILE INTEGRITY MONITOR");
            System.out.println("========================================");
            System.out.println("Monitored folder: " + MONITORED_FOLDER.toAbsolutePath());

            if (command.equals("init")) {
                baselineManager.createBaseline(MONITORED_FOLDER);
                baselineManager.saveBaselineToFile(BASELINE_FILE);
                System.out.println("BASELINE CREATED SUCCESSFULLY");
                System.out.println("Files recorded: " + baselineManager.getBaselineEntries().size());
                System.out.println("Run the application again to scan for changes.");
                return;
            }

            if (!command.equals("scan")) {
                System.out.println("Unknown command. Use: init or scan");
                return;
            }

            if (!Files.exists(BASELINE_FILE)) {
                baselineManager.createBaseline(MONITORED_FOLDER);
                baselineManager.saveBaselineToFile(BASELINE_FILE);
                System.out.println("No baseline was found. A new baseline has been created.");
                System.out.println("Change or add a file in the monitored folder, then run again.");
                return;
            }

            baselineManager.loadBaselineFromFile(BASELINE_FILE);
            DirectoryScanner scanner = new DirectoryScanner();
            ScanReport report = scanner.scan(MONITORED_FOLDER,
                    baselineManager.getBaselineEntries());
            printReport(report);

        } catch (Exception exception) {
            System.err.println("ERROR: " + exception.getMessage());
        }
    }

    private static void printReport(ScanReport report) {
        System.out.println("SCAN REPORT");
        System.out.println("----------------------------------------");
        for (FileScanResult result : report.results()) {
            String relativePath;
            try {
                relativePath = Path.of(".").toAbsolutePath().normalize()
                        .relativize(result.path().toAbsolutePath().normalize()).toString();
            } catch (IllegalArgumentException exception) {
                relativePath = result.path().toString();
            }
            System.out.printf("[%-8s] %s%n", result.status(), relativePath);
        }
        System.out.println("----------------------------------------");
        System.out.println("Total:    " + report.totalFiles());
        System.out.println("SAFE:     " + report.count(FileStatus.SAFE));
        System.out.println("MODIFIED: " + report.count(FileStatus.MODIFIED));
        System.out.println("NEW:      " + report.count(FileStatus.NEW));
        System.out.println("DELETED:  " + report.count(FileStatus.DELETED));
        System.out.println("ERROR:    " + report.count(FileStatus.ERROR));
        System.out.println("========================================");
        System.out.println("Note: baseline is not changed during a scan.");
        System.out.println("Use 'init' only when you intentionally want a new baseline.");
    }
}
