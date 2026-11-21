package kz.kaznaru.fim.database;

import kz.kaznaru.fim.model.FileStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Persists and queries integrity scan history. */
public final class EventRepository {

    private final DatabaseManager databaseManager;

    public EventRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void save(FileEventRecord event) throws SQLException {
        databaseManager.inTransaction(connection -> {
            save(connection, event);
            return null;
        });
    }

    public List<FileEventRecord> findAll() throws SQLException {
        return databaseManager.withConnection(this::findAll);
    }

    public List<FileEventRecord> findByPath(String monitorRoot, String filePath)
            throws SQLException {
        return databaseManager.withConnection(
                connection -> findByPath(connection, monitorRoot, filePath));
    }

    public List<FileEventRecord> findAll(Connection connection) throws SQLException {
        String sql = """
                SELECT id, monitored_file_id, monitor_root, file_path, event_type,
                       old_hash, new_hash, details, occurred_at_utc
                FROM file_events
                ORDER BY id
                """;
        List<FileEventRecord> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                events.add(mapRecord(resultSet));
            }
        }
        return events;
    }

    public List<FileEventRecord> findByPath(
            Connection connection, String monitorRoot, String filePath) throws SQLException {
        String sql = """
                SELECT id, monitored_file_id, monitor_root, file_path, event_type,
                       old_hash, new_hash, details, occurred_at_utc
                FROM file_events
                WHERE monitor_root = ? AND file_path = ?
                ORDER BY id
                """;
        List<FileEventRecord> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, monitorRoot);
            statement.setString(2, filePath);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    events.add(mapRecord(resultSet));
                }
            }
        }
        return events;
    }

    public void save(Connection connection, FileEventRecord event) throws SQLException {
        String sql = """
                INSERT INTO file_events
                    (monitored_file_id, monitor_root, file_path, event_type, old_hash,
                     new_hash, details, occurred_at_utc)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, event.monitoredFileId());
            statement.setString(2, event.monitorRoot());
            statement.setString(3, event.filePath());
            statement.setString(4, event.eventType().name());
            statement.setString(5, event.oldHash());
            statement.setString(6, event.newHash());
            statement.setString(7, event.details());
            statement.setString(8, event.occurredAtUtc());
            statement.executeUpdate();
        }
    }

    private FileEventRecord mapRecord(ResultSet resultSet) throws SQLException {
        return new FileEventRecord(
                resultSet.getLong("id"),
                resultSet.getLong("monitored_file_id"),
                resultSet.getString("monitor_root"),
                resultSet.getString("file_path"),
                FileStatus.valueOf(resultSet.getString("event_type")),
                resultSet.getString("old_hash"),
                resultSet.getString("new_hash"),
                resultSet.getString("details"),
                resultSet.getString("occurred_at_utc"));
    }
}
