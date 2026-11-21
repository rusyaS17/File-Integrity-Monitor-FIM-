package kz.kaznaru.fim.core;

import kz.kaznaru.fim.model.FileScanResult;
import kz.kaznaru.fim.model.FileStatus;
import kz.kaznaru.fim.model.ScanReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Compares all files in a folder with the saved baseline. */
public class DirectoryScanner {

    private final IntegrityChecker integrityChecker;

    public DirectoryScanner() {
        this.integrityChecker = new IntegrityChecker();
    }

    public DirectoryScanner(IntegrityChecker integrityChecker) {
        if (integrityChecker == null) {
            throw new IllegalArgumentException("IntegrityChecker must not be null");
        }
        this.integrityChecker = integrityChecker;
    }

    public ScanReport scan(Path directory, Map<String, String> baseline) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Monitored folder does not exist: " + directory);
        }

        List<FileScanResult> results = new ArrayList<>();
        Set<String> foundPaths = new HashSet<>();

        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String key = normalize(path);
                foundPaths.add(key);
                String originalHash = baseline.get(key);

                try {
                    if (originalHash == null) {
                        integrityChecker.getCurrentHash(path);
                        results.add(new FileScanResult(path, FileStatus.NEW,
                                "File is not present in the baseline"));
                    } else if (!integrityChecker.isFileModified(path, originalHash)) {
                        results.add(new FileScanResult(path, FileStatus.SAFE,
                                "SHA-256 matches the baseline"));
                    } else {
                        results.add(new FileScanResult(path, FileStatus.MODIFIED,
                                "SHA-256 does not match the baseline"));
                    }
                } catch (IOException exception) {
                    results.add(new FileScanResult(path, FileStatus.ERROR,
                            exception.getMessage()));
                }
            }
        }

        for (String baselinePath : baseline.keySet()) {
            if (!foundPaths.contains(baselinePath)) {
                results.add(new FileScanResult(Path.of(baselinePath), FileStatus.DELETED,
                        "File from the baseline was not found"));
            }
        }

        results.sort((first, second) -> first.path().toString()
                .compareToIgnoreCase(second.path().toString()));
        return new ScanReport(List.copyOf(results));
    }

    private String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
