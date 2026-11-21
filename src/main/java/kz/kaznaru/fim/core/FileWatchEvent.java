package kz.kaznaru.fim.core;

import java.nio.file.Path;

public record FileWatchEvent(Path path, Kind kind, String details) {

    public enum Kind {
        CREATE, MODIFY, DELETE, OVERFLOW, ERROR
    }
}
