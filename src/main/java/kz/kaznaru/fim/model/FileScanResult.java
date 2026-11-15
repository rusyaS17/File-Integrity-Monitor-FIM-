package kz.kaznaru.fim.model;

import java.nio.file.Path;

public record FileScanResult(Path path, FileStatus status, String details) {
}
