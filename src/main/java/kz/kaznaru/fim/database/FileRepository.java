package kz.kaznaru.fim.database;

import kz.kaznaru.fim.model.FileStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Stores the latest observed state for each file under each monitored root. */
public final class FileRepository {

    private final DatabaseManager databaseManager;

    public FileRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public Optional<MonitoredFileRecord> findByPath(String monitorRoot, String filePath)
            throws SQLException {
        return databaseManager.withConnection(
                connection -> findByPath(connection, monitorRoot, filePath));
    }

    public List<MonitoredFileRecord> findByRoot(String monitorRoot) throws SQLException {
        return databaseManager.withConnection(connection -> findByRoot(connection, monitorRoot));
    }

    public long save(MonitoredFileRecord file) throws SQLException {
        return databaseManager.inTransaction(connection -> save(connection, file));
    }

    public boolean deleteByPath(String monitorRoot, String filePath) throws SQLException {
        return databaseManager.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM monitored_files WHERE monitor_root = ? AND file_path = ?")) {
                statement.setString(1, monitorRoot);
                statement.setString(2, filePath);
                return statement.executeUpdate() > 0;
            }
        });
    }

    public Optional<MonitoredFileRecord> findByPath(
            Connection connection, String monitorRoot, String filePath) throws SQLException {
        String sql = """
                SELECT id, monitor_root, file_path, sha256_hash, status, details, last_seen_utc
                FROM monitored_files
                WHERE monitor_root = ? AND file_path = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, monitorRoot);
            statement.setString(2, filePath);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(mapRecord(resultSet))
                        : Optional.empty();
            }
        }
    }

    public List<MonitoredFileRecord> findByRoot(Connection connection, String monitorRoot)
            throws SQLException {
        String sql = """
                SELECT id, monitor_root, file_path, sha256_hash, status, details, last_seen_utc
                FROM monitored_files
                WHERE monitor_root = ?
                ORDER BY file_path
                """;
        List<MonitoredFileRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, monitorRoot);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(mapRecord(resultSet));
                }
            }
        }
        return records;
    }

    public long save(Connection connection, MonitoredFileRecord file) throws SQLException {
        String sql = """
                INSERT INTO monitored_files
                    (monitor_root, file_path, sha256_hash, status, details, last_seen_utc)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (monitor_root, file_path) DO UPDATE SET
                    sha256_hash = COALESCE(excluded.sha256_hash, monitored_files.sha256_hash),
                    status = excluded.status,
                    details = excluded.details,
                    last_seen_utc = excluded.last_seen_utc
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, file.monitorRoot());
            statement.setString(2, file.filePath());
            statement.setString(3, file.sha256Hash());
            statement.setString(4, file.status().name());
            statement.setString(5, file.details());
            statement.setString(6, file.lastSeenUtc());
            statement.executeUpdate();
        }
        return findByPath(connection, file.monitorRoot(), file.filePath())
                .orElseThrow(() -> new SQLException("Saved monitored file could not be reloaded"))
                .id();
    }

    private MonitoredFileRecord mapRecord(ResultSet resultSet) throws SQLException {
        return new MonitoredFileRecord(
                resultSet.getLong("id"),
                resultSet.getString("monitor_root"),
                resultSet.getString("file_path"),
                resultSet.getString("sha256_hash"),
                FileStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("details"),
                resultSet.getString("last_seen_utc"));
    }
}
