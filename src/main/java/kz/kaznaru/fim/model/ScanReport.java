package kz.kaznaru.fim.model;

import java.util.List;

public record ScanReport(List<FileScanResult> results) {

    public long count(FileStatus status) {
        return results.stream().filter(result -> result.status() == status).count();
    }

    public int totalFiles() {
        return results.size();
    }
}
