package kz.kaznaru.fim.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Creates SQLite connections and owns schema initialization and transactions. */
public final class DatabaseManager {

    private static final String[] SCHEMA_STATEMENTS = {
            """
            CREATE TABLE IF NOT EXISTS monitored_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                monitor_root TEXT NOT NULL,
                file_path TEXT NOT NULL,
                sha256_hash TEXT,
                status TEXT NOT NULL CHECK (status IN
                    ('SAFE', 'MODIFIED', 'NEW', 'DELETED', 'ERROR')),
                details TEXT,
                last_seen_utc TEXT NOT NULL,
                UNIQUE (monitor_root, file_path)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS file_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                monitored_file_id INTEGER NOT NULL,
                monitor_root TEXT NOT NULL,
                file_path TEXT NOT NULL,
                event_type TEXT NOT NULL CHECK (event_type IN
                    ('SAFE', 'MODIFIED', 'NEW', 'DELETED', 'ERROR')),
                old_hash TEXT,
                new_hash TEXT,
                details TEXT,
                occurred_at_utc TEXT NOT NULL,
                FOREIGN KEY (monitored_file_id)
                    REFERENCES monitored_files(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_monitored_files_root_status
                ON monitored_files (monitor_root, status)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_file_events_root_time
                ON file_events (monitor_root, occurred_at_utc DESC)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_file_events_path_time
                ON file_events (monitor_root, file_path, occurred_at_utc DESC)
            """
    };

    private final Path databaseFile;

    public DatabaseManager(Path databaseFile) {
        if (databaseFile == null) {
            throw new IllegalArgumentException("Database path must not be null");
        }
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
    }

    public Path databaseFile() {
        return databaseFile;
    }

    public void initializeSchema() throws SQLException, IOException {
        Path parent = databaseFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        inTransaction(connection -> {
            for (String schemaStatement : SCHEMA_STATEMENTS) {
                try (PreparedStatement statement = connection.prepareStatement(schemaStatement)) {
                    statement.execute();
                }
            }
            return null;
        });
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try {
            try (PreparedStatement statement = connection.prepareStatement("PRAGMA foreign_keys = ON")) {
                statement.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement("PRAGMA busy_timeout = 5000")) {
                statement.execute();
            }
            return connection;
        } catch (SQLException exception) {
            try {
                connection.close();
            } catch (SQLException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    public <T> T withConnection(ConnectionWork<T> work) throws SQLException {
        try (Connection connection = openConnection()) {
            return work.execute(connection);
        }
    }

    public <T> T inTransaction(ConnectionWork<T> work) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            }
        }
    }

    @FunctionalInterface
    public interface ConnectionWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
