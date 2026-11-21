package kz.kaznaru.fim.database;

import kz.kaznaru.fim.model.FileStatus;
import kz.kaznaru.fim.model.EventActivityBucket;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Persists and queries integrity scan history. */
public final class EventRepository {

    private static final DateTimeFormatter UTC_TIMESTAMP_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    private static final String RECENT_EVENTS_COLUMNS = """
            SELECT id, monitored_file_id, monitor_root, file_path, event_type,
                   old_hash, new_hash, details, occurred_at_utc
            FROM file_events
            """;

    private static final String ACTIVITY_SQL = """
            WITH ranged_events AS (
                SELECT CAST(
                           strftime('%s', substr(occurred_at_utc, 1, 19) || 'Z')
                           AS INTEGER
                       ) AS epoch_second,
                       event_type
                FROM file_events
                WHERE monitor_root = ?
                  AND (
                      substr(occurred_at_utc, 1, 19) || '.' ||
                      substr(
                          replace(substr(occurred_at_utc, 20), 'Z', '')
                              || '0000000000',
                          2,
                          9
                      ) || 'Z'
                  ) >= ?
                  AND (
                      substr(occurred_at_utc, 1, 19) || '.' ||
                      substr(
                          replace(substr(occurred_at_utc, 20), 'Z', '')
                              || '0000000000',
                          2,
                          9
                      ) || 'Z'
                  ) < ?
            ),
            bucketed_events AS (
                SELECT epoch_second - ((epoch_second % ? + ?) % ?) AS bucket_epoch,
                       event_type
                FROM ranged_events
            )
            SELECT bucket_epoch, event_type, COUNT(*) AS event_count
            FROM bucketed_events
            GROUP BY bucket_epoch, event_type
            ORDER BY bucket_epoch ASC
            """;

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

    public List<FileEventRecord> findRecentByRoot(
            String monitorRoot, int limit, int offset) throws SQLException {
        return findRecentByRoot(monitorRoot, limit, offset, Optional.empty());
    }

    public List<FileEventRecord> findRecentByRoot(
            String monitorRoot,
            int limit,
            int offset,
            Optional<FileStatus> eventTypeFilter) throws SQLException {
        Objects.requireNonNull(monitorRoot, "monitorRoot");
        Objects.requireNonNull(eventTypeFilter, "eventTypeFilter");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }

        String filterClause = eventTypeFilter.isPresent() ? " AND event_type = ?" : "";
        String sql = RECENT_EVENTS_COLUMNS + """
                WHERE monitor_root = ?
                """ + filterClause + """
                ORDER BY (
                    substr(occurred_at_utc, 1, 19) || '.' ||
                    substr(
                        replace(substr(occurred_at_utc, 20), 'Z', '')
                            || '0000000000',
                        2,
                        9
                    ) || 'Z'
                ) DESC, id DESC
                LIMIT ? OFFSET ?
                """;

        return databaseManager.withConnection(connection -> {
            List<FileEventRecord> events = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameterIndex = 1;
                statement.setString(parameterIndex++, monitorRoot);
                if (eventTypeFilter.isPresent()) {
                    statement.setString(parameterIndex++, eventTypeFilter.orElseThrow().name());
                }
                statement.setInt(parameterIndex++, limit);
                statement.setInt(parameterIndex, offset);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        events.add(mapRecord(resultSet));
                    }
                }
            }
            return List.copyOf(events);
        });
    }

    public List<EventActivityBucket> findActivityByRoot(
            String monitorRoot,
            Instant fromInclusive,
            Instant toExclusive,
            Duration bucketSize) throws SQLException {
        Objects.requireNonNull(monitorRoot, "monitorRoot");
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        Objects.requireNonNull(bucketSize, "bucketSize");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
        if (bucketSize.isNegative() || bucketSize.isZero()
                || bucketSize.getNano() != 0 || bucketSize.getSeconds() <= 0) {
            throw new IllegalArgumentException(
                    "bucketSize must be a positive whole number of seconds");
        }
        long bucketSeconds = bucketSize.getSeconds();

        return databaseManager.withConnection(connection -> {
            Map<Instant, EnumMap<FileStatus, Long>> countsByBucket = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(ACTIVITY_SQL)) {
                statement.setString(1, monitorRoot);
                statement.setString(2, UTC_TIMESTAMP_FORMATTER.format(fromInclusive));
                statement.setString(3, UTC_TIMESTAMP_FORMATTER.format(toExclusive));
                statement.setLong(4, bucketSeconds);
                statement.setLong(5, bucketSeconds);
                statement.setLong(6, bucketSeconds);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Instant bucketStart = Instant.ofEpochSecond(
                                resultSet.getLong("bucket_epoch"));
                        FileStatus eventType =
                                FileStatus.valueOf(resultSet.getString("event_type"));
                        long count = resultSet.getLong("event_count");
                        countsByBucket.computeIfAbsent(
                                        bucketStart, ignored -> new EnumMap<>(FileStatus.class))
                                .put(eventType, count);
                    }
                }
            }

            List<EventActivityBucket> buckets = new ArrayList<>(countsByBucket.size());
            countsByBucket.forEach((start, counts) ->
                    buckets.add(new EventActivityBucket(start, counts)));
            return List.copyOf(buckets);
        });
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
