package kz.kaznaru.fim.core;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Recursively registers a root and forwards Java NIO file-system events. */
public final class FileWatcher implements AutoCloseable {

    private final Path root;
    private final WatchService watchService;
    private final Object registrationLock = new Object();
    private final Map<WatchKey, Path> directoriesByKey = new HashMap<>();
    private final Map<Path, WatchKey> keysByDirectory = new HashMap<>();
    private volatile boolean closed;

    public FileWatcher(Path root) throws IOException {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Monitored root is not a directory: " + this.root);
        }

        watchService = FileSystems.getDefault().newWatchService();
        try {
            registerTree(this.root);
        } catch (IOException | RuntimeException exception) {
            try {
                watchService.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    public Path root() {
        return root;
    }

    public int registeredDirectoryCount() {
        synchronized (registrationLock) {
            return keysByDirectory.size();
        }
    }

    public void registerExistingDirectories() throws IOException {
        if (closed) {
            return;
        }
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            registerTree(root);
        }
    }

    public void watch(EventHandler handler) throws IOException {
        Objects.requireNonNull(handler, "handler");
        while (!closed) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException exception) {
                return;
            }

            Path directory;
            synchronized (registrationLock) {
                directory = directoriesByKey.get(key);
            }
            if (directory == null) {
                key.cancel();
                continue;
            }

            for (WatchEvent<?> watchEvent : key.pollEvents()) {
                if (watchEvent.kind() == StandardWatchEventKinds.OVERFLOW) {
                    handler.onEvent(new FileWatchEvent(root, FileWatchEvent.Kind.OVERFLOW,
                            "WatchService reported OVERFLOW"));
                    continue;
                }

                Object context = watchEvent.context();
                if (!(context instanceof Path relativePath)) {
                    continue;
                }
                Path changedPath = directory.resolve(relativePath).toAbsolutePath().normalize();
                FileWatchEvent.Kind kind = mapKind(watchEvent.kind());
                if (kind == null) {
                    continue;
                }

                if (kind == FileWatchEvent.Kind.CREATE
                        && Files.isDirectory(changedPath, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        registerTree(changedPath);
                    } catch (IOException exception) {
                        handler.onEvent(new FileWatchEvent(changedPath,
                                FileWatchEvent.Kind.ERROR, exception.getMessage()));
                    }
                }
                handler.onEvent(new FileWatchEvent(changedPath, kind, null));
            }

            if (!key.reset()) {
                removeKey(key, directory);
                if (!closed && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        registerTree(directory);
                    } catch (IOException exception) {
                        handler.onEvent(new FileWatchEvent(directory,
                                FileWatchEvent.Kind.ERROR, exception.getMessage()));
                    }
                }
            }
        }
    }

    private void registerTree(Path start) throws IOException {
        try (Stream<Path> paths = Files.walk(start)) {
            for (Path path : paths.filter(
                    candidate -> Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)).toList()) {
                registerDirectory(path);
            }
        }
    }

    private void registerDirectory(Path directory) throws IOException {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        synchronized (registrationLock) {
            if (closed) {
                return;
            }

            WatchKey existingKey = keysByDirectory.get(normalizedDirectory);
            if (existingKey != null && existingKey.isValid()) {
                return;
            }
            if (existingKey != null) {
                keysByDirectory.remove(normalizedDirectory);
                directoriesByKey.remove(existingKey);
            }

            WatchKey key = normalizedDirectory.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            keysByDirectory.put(normalizedDirectory, key);
            directoriesByKey.put(key, normalizedDirectory);
        }
    }

    private void removeKey(WatchKey key, Path directory) {
        synchronized (registrationLock) {
            directoriesByKey.remove(key);
            keysByDirectory.remove(directory, key);
        }
    }

    private FileWatchEvent.Kind mapKind(WatchEvent.Kind<?> kind) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            return FileWatchEvent.Kind.CREATE;
        }
        if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            return FileWatchEvent.Kind.MODIFY;
        }
        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            return FileWatchEvent.Kind.DELETE;
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        synchronized (registrationLock) {
            for (WatchKey key : new ArrayList<>(directoriesByKey.keySet())) {
                key.cancel();
            }
            directoriesByKey.clear();
            keysByDirectory.clear();
        }
        watchService.close();
    }

    @FunctionalInterface
    public interface EventHandler {
        void onEvent(FileWatchEvent event);
    }
}
