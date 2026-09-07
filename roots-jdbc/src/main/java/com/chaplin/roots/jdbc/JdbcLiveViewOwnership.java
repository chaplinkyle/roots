package com.chaplin.roots.jdbc;

import com.chaplin.roots.LiveViewOwner;
import com.chaplin.roots.LiveViewOwnership;
import com.chaplin.roots.LiveViewOwnershipException;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A shared {@link LiveViewOwnership} registry backed by a JDBC data source.
 *
 * <p>The adapter uses only standard JDBC and deliberately does not own the data
 * source or its connection pool. Each method borrows and closes a connection.
 * Expiry instants are persisted as UTC epoch milliseconds, avoiding database
 * time-zone and timestamp-precision differences.</p>
 *
 * <p>Install the statements returned by {@link #schemaStatements()} through the
 * application's migration tool. {@link #createSchema(DataSource)} is a
 * convenience for tests and first-run setup and is intentionally not
 * idempotent.</p>
 */
public final class JdbcLiveViewOwnership implements LiveViewOwnership {
    /** Default unqualified ownership table name. */
    public static final String DEFAULT_TABLE = "roots_live_view_ownership";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,51}");

    private final DataSource dataSource;
    private final String nodeId;
    private final String table;

    /**
     * Creates an adapter using {@link #DEFAULT_TABLE}.
     *
     * @param dataSource externally managed JDBC data source
     * @param nodeId stable identifier unique to this running application node
     */
    public JdbcLiveViewOwnership(DataSource dataSource, String nodeId) {
        this(dataSource, nodeId, DEFAULT_TABLE);
    }

    /**
     * Creates an adapter using an unqualified custom table name.
     *
     * @param dataSource externally managed JDBC data source
     * @param nodeId stable identifier unique to this running application node
     * @param table unquoted SQL identifier used for the ownership table
     */
    public JdbcLiveViewOwnership(DataSource dataSource, String nodeId, String table) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.nodeId = new LiveViewOwner(nodeId, "roots-jdbc-validation", Instant.EPOCH).nodeId();
        this.table = validateTable(table);
    }

    /**
     * Returns portable schema statements for {@link #DEFAULT_TABLE}.
     *
     * @return immutable create-table and expiry-index statements
     */
    public static List<String> schemaStatements() {
        return schemaStatements(DEFAULT_TABLE);
    }

    /**
     * Returns portable schema statements for a custom table.
     *
     * @param table unquoted SQL identifier
     * @return immutable create-table and expiry-index statements
     */
    public static List<String> schemaStatements(String table) {
        var validated = validateTable(table);
        return List.of(
                "CREATE TABLE " + validated + " ("
                        + "view_id VARCHAR(512) PRIMARY KEY, "
                        + "node_id VARCHAR(256) NOT NULL, "
                        + "session_id VARCHAR(512) NOT NULL, "
                        + "expires_at_epoch_ms BIGINT NOT NULL)",
                "CREATE INDEX " + validated + "_expiry_idx ON " + validated + " (expires_at_epoch_ms)"
        );
    }

    /**
     * Executes {@link #schemaStatements()} once.
     *
     * @param dataSource target data source
     * @throws LiveViewOwnershipException when a statement cannot be executed
     */
    public static void createSchema(DataSource dataSource) {
        createSchema(dataSource, DEFAULT_TABLE);
    }

    /**
     * Executes {@link #schemaStatements(String)} once.
     *
     * @param dataSource target data source
     * @param table unquoted SQL identifier
     * @throws LiveViewOwnershipException when a statement cannot be executed
     */
    public static void createSchema(DataSource dataSource, String table) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            for (var sql : schemaStatements(table)) {
                statement.execute(sql);
            }
        } catch (SQLException exception) {
            throw failure("create schema", exception);
        }
    }

    @Override
    public String localNodeId() {
        return nodeId;
    }

    @Override
    public boolean claim(String viewId, String sessionId, Instant now, Instant expiresAt) {
        validateLease(viewId, sessionId, now, expiresAt);
        if (updateClaim(viewId, sessionId, now, expiresAt)) {
            return true;
        }
        try {
            insert(viewId, sessionId, expiresAt);
            return true;
        } catch (LiveViewOwnershipException exception) {
            if (!constraintViolation(exception.getCause())) {
                throw exception;
            }
            return updateClaim(viewId, sessionId, now, expiresAt);
        }
    }

    @Override
    public boolean renew(String viewId, String sessionId, Instant now, Instant expiresAt) {
        validateLease(viewId, sessionId, now, expiresAt);
        var sql = "UPDATE " + table + " SET expires_at_epoch_ms = ? "
                + "WHERE view_id = ? AND node_id = ? AND session_id = ? AND expires_at_epoch_ms > ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, epochMillis(expiresAt, "expiresAt"));
            statement.setString(2, viewId);
            statement.setString(3, nodeId);
            statement.setString(4, sessionId);
            statement.setLong(5, epochMillis(now, "now"));
            return changed(statement.executeUpdate(), "renew");
        } catch (SQLException exception) {
            throw failure("renew", exception);
        }
    }

    @Override
    public Optional<LiveViewOwner> find(String viewId, Instant now) {
        validateViewId(viewId);
        Objects.requireNonNull(now, "now");
        var sql = "SELECT node_id, session_id, expires_at_epoch_ms FROM " + table
                + " WHERE view_id = ? AND expires_at_epoch_ms > ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, viewId);
            statement.setLong(2, epochMillis(now, "now"));
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                var owner = new LiveViewOwner(
                        result.getString(1),
                        result.getString(2),
                        Instant.ofEpochMilli(result.getLong(3))
                );
                if (result.next()) {
                    throw new LiveViewOwnershipException("JDBC ownership lookup returned duplicate rows");
                }
                return Optional.of(owner);
            }
        } catch (SQLException exception) {
            throw failure("find", exception);
        }
    }

    @Override
    public void release(String viewId, String sessionId) {
        validateOwner(viewId, sessionId, Instant.EPOCH);
        var sql = "DELETE FROM " + table + " WHERE view_id = ? AND node_id = ? AND session_id = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, viewId);
            statement.setString(2, nodeId);
            statement.setString(3, sessionId);
            changed(statement.executeUpdate(), "release");
        } catch (SQLException exception) {
            throw failure("release", exception);
        }
    }

    @Override
    public void deleteExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        var sql = "DELETE FROM " + table + " WHERE expires_at_epoch_ms <= ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, epochMillis(now, "now"));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("delete expired", exception);
        }
    }

    private boolean updateClaim(String viewId, String sessionId, Instant now, Instant expiresAt) {
        var sql = "UPDATE " + table + " SET node_id = ?, session_id = ?, expires_at_epoch_ms = ? "
                + "WHERE view_id = ? AND (expires_at_epoch_ms <= ? OR (node_id = ? AND session_id = ?))";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, nodeId);
            statement.setString(2, sessionId);
            statement.setLong(3, epochMillis(expiresAt, "expiresAt"));
            statement.setString(4, viewId);
            statement.setLong(5, epochMillis(now, "now"));
            statement.setString(6, nodeId);
            statement.setString(7, sessionId);
            return changed(statement.executeUpdate(), "claim");
        } catch (SQLException exception) {
            throw failure("claim", exception);
        }
    }

    private void insert(String viewId, String sessionId, Instant expiresAt) {
        var sql = "INSERT INTO " + table
                + " (view_id, node_id, session_id, expires_at_epoch_ms) VALUES (?, ?, ?, ?)";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, viewId);
            statement.setString(2, nodeId);
            statement.setString(3, sessionId);
            statement.setLong(4, epochMillis(expiresAt, "expiresAt"));
            if (statement.executeUpdate() != 1) {
                throw new LiveViewOwnershipException("JDBC ownership insert did not create exactly one row");
            }
        } catch (SQLException exception) {
            throw failure("claim", exception);
        }
    }

    private static boolean changed(int rows, String operation) {
        if (rows < 0 || rows > 1) {
            throw new LiveViewOwnershipException("JDBC ownership " + operation + " changed an invalid row count");
        }
        return rows == 1;
    }

    private static boolean constraintViolation(Throwable failure) {
        if (!(failure instanceof SQLException sqlException)) {
            return false;
        }
        var state = sqlException.getSQLState();
        return sqlException instanceof SQLIntegrityConstraintViolationException
                || state != null && state.startsWith("23");
    }

    private static void validateLease(String viewId, String sessionId, Instant now, Instant expiresAt) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(expiresAt, "expiresAt");
        validateOwner(viewId, sessionId, expiresAt);
        epochMillis(now, "now");
        epochMillis(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Live-view lease expiry must be after the current instant");
        }
    }

    private static void validateOwner(String viewId, String sessionId, Instant expiresAt) {
        validateViewId(viewId);
        new LiveViewOwner("roots-jdbc-validation", sessionId, expiresAt);
    }

    private static void validateViewId(String viewId) {
        Objects.requireNonNull(viewId, "viewId");
        if (viewId.isBlank() || viewId.length() > 512 || viewId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("View id must be nonblank, control-free, and at most 512 characters");
        }
    }

    private static long epochMillis(Instant instant, String name) {
        try {
            return instant.toEpochMilli();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is outside the supported epoch-millisecond range", exception);
        }
    }

    private static String validateTable(String table) {
        Objects.requireNonNull(table, "table");
        if (!IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException(
                    "Ownership table must be an unquoted SQL identifier of at most 52 characters");
        }
        return table;
    }

    private static LiveViewOwnershipException failure(String operation, SQLException cause) {
        return new LiveViewOwnershipException("JDBC live-view ownership could not " + operation, cause);
    }
}
