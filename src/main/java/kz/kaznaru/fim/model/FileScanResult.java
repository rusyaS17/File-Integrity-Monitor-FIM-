package kz.kaznaru.fim.model;

import java.nio.file.Path;

public record FileScanResult(
        Path path,
        FileStatus status,
        String details,
        String sha256Hash,
        String baselineSha256Hash) {

    public FileScanResult(Path path, FileStatus status, String details) {
        this(path, status, details, null, null);
    }

    public FileScanResult(Path path, FileStatus status, String details, String sha256Hash) {
        this(path, status, details, sha256Hash, null);
    }
}
