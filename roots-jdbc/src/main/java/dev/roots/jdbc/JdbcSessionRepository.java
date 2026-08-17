package dev.roots.jdbc;

import dev.roots.Session;
import dev.roots.SessionRepository;
import dev.roots.SessionRepositoryException;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A distributed JDBC {@link SessionRepository} with database-backed values.
 *
 * <p>The adapter owns neither the data source nor its pool. A singleton mutex
 * row serializes session creation so the configured capacity remains exact
 * across application nodes. Session values are read and written through a
 * {@link JdbcSessionValueCodec}; Java object serialization is never used.</p>
 */
public final class JdbcSessionRepository implements SessionRepository {
    /** Default unqualified session table name. */
    public static final String DEFAULT_TABLE = "roots_sessions";
    /** Maximum encoded value length accepted by the bundled schema. */
    public static final int MAX_ENCODED_VALUE_LENGTH = 8192;
    private static final int MAX_KEY_LENGTH = 255;
    private static final int TOKEN_ATTEMPTS = 10;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataSource dataSource;
    private final JdbcSessionValueCodec codec;
    private final String table;
    private final String valuesTable;
    private final String mutexTable;

    /** Creates a repository using the default table and scalar codec.
     * @param dataSource externally managed JDBC data source
     */
    public JdbcSessionRepository(DataSource dataSource) {
        this(dataSource, JdbcSessionValueCodec.standard(), DEFAULT_TABLE);
    }

    /** Creates a repository using the default table and a custom value codec.
     * @param dataSource externally managed JDBC data source
     * @param codec thread-safe application value codec
     */
    public JdbcSessionRepository(DataSource dataSource, JdbcSessionValueCodec codec) {
        this(dataSource, codec, DEFAULT_TABLE);
    }

    /** Creates a repository using a custom unqualified base table name.
     * @param dataSource externally managed JDBC data source
     * @param codec thread-safe application value codec
     * @param table base table; value and mutex table names are derived from it
     */
    public JdbcSessionRepository(DataSource dataSource, JdbcSessionValueCodec codec, String table) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.table = validateTable(table);
        valuesTable = this.table + "_values";
        mutexTable = this.table + "_mutex";
    }

    /** Returns portable schema statements for the default tables.
     * @return immutable ordered schema statements
     */
    public static List<String> schemaStatements() {
        return schemaStatements(DEFAULT_TABLE);
    }

    /** Returns portable schema statements for a custom base table.
     * @param table unquoted SQL base identifier
     * @return immutable ordered schema statements
     */
    public static List<String> schemaStatements(String table) {
        var validated = validateTable(table);
        var values = validated + "_values";
        var mutex = validated + "_mutex";
        return List.of(
                "CREATE TABLE " + validated + " ("
                        + "session_id VARCHAR(512) PRIMARY KEY, "
                        + "expires_at_epoch_ms BIGINT NOT NULL)",
                "CREATE INDEX " + validated + "_expiry_idx ON " + validated + " (expires_at_epoch_ms)",
                "CREATE TABLE " + mutex + " (lock_id INTEGER PRIMARY KEY)",
                "INSERT INTO " + mutex + " (lock_id) VALUES (1)",
                "CREATE TABLE " + values + " ("
                        + "session_id VARCHAR(512) NOT NULL, "
                        + "value_name VARCHAR(255) NOT NULL, "
                        + "encoded_value VARCHAR(8192) NOT NULL, "
                        + "PRIMARY KEY (session_id, value_name), "
                        + "FOREIGN KEY (session_id) REFERENCES " + validated
                        + " (session_id) ON DELETE CASCADE)"
        );
    }

    /** Creates the default schema once for tests or first-run tooling.
     * @param dataSource target data source
     */
    public static void createSchema(DataSource dataSource) {
        createSchema(dataSource, DEFAULT_TABLE);
    }

    /** Creates a custom schema once for tests or first-run tooling.
     * @param dataSource target data source
     * @param table unquoted SQL base identifier
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
    public Optional<Session> findAndTouch(String id, Instant accessedAt, Instant expiresAt) {
        new Session(id);
        validateWindow(accessedAt, expiresAt);
        var sql = "UPDATE " + table + " SET expires_at_epoch_ms = ? "
                + "WHERE session_id = ? AND expires_at_epoch_ms > ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, epochMillis(expiresAt, "expiresAt"));
            statement.setString(2, id);
            statement.setLong(3, epochMillis(accessedAt, "accessedAt"));
            return changed(statement.executeUpdate(), "touch")
                    ? Optional.of(new JdbcSession(id)) : Optional.empty();
        } catch (SQLException exception) {
            throw failure("touch session", exception);
        }
    }

    @Override
    public Optional<Session> create(Instant expiresAt, int maximumSessions) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        epochMillis(expiresAt, "expiresAt");
        if (maximumSessions < 1) {
            throw new IllegalArgumentException("Maximum sessions must be positive");
        }
        for (var attempt = 0; attempt < TOKEN_ATTEMPTS; attempt++) {
            var id = token();
            try (var connection = dataSource.getConnection()) {
                var originalAutoCommit = connection.getAutoCommit();
                try {
                    connection.setAutoCommit(false);
                    lockCapacity(connection);
                    if (count(connection) >= maximumSessions) {
                        connection.rollback();
                        return Optional.empty();
                    }
                    insertSession(connection, id, expiresAt);
                    connection.commit();
                    return Optional.of(new JdbcSession(id));
                } catch (SQLException exception) {
                    rollback(connection, exception);
                    if (!constraintViolation(exception)) {
                        throw failure("create session", exception);
                    }
                } finally {
                    restoreAutoCommit(connection, originalAutoCommit);
                }
            } catch (SQLException exception) {
                throw failure("create session", exception);
            }
        }
        throw new SessionRepositoryException("Could not generate a unique JDBC session identifier");
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
            throw failure("delete expired sessions", exception);
        }
    }

    @Override
    public int size() {
        try (var connection = dataSource.getConnection()) {
            var count = count(connection);
            if (count > Integer.MAX_VALUE) {
                throw new SessionRepositoryException("JDBC session count exceeds the supported integer range");
            }
            return (int) count;
        } catch (SQLException exception) {
            throw failure("count sessions", exception);
        }
    }

    private void lockCapacity(Connection connection) throws SQLException {
        var sql = "SELECT lock_id FROM " + mutexTable + " WHERE lock_id = 1 FOR UPDATE";
        try (var statement = connection.prepareStatement(sql);
             var result = statement.executeQuery()) {
            if (!result.next() || result.getInt(1) != 1 || result.next()) {
                throw new SessionRepositoryException("JDBC session capacity mutex is missing or corrupt");
            }
        }
    }

    private long count(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             var result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SessionRepositoryException("JDBC session count returned no row");
            }
            var count = result.getLong(1);
            if (count < 0 || result.next()) {
                throw new SessionRepositoryException("JDBC session count returned an invalid result");
            }
            return count;
        }
    }

    private void insertSession(Connection connection, String id, Instant expiresAt) throws SQLException {
        var sql = "INSERT INTO " + table + " (session_id, expires_at_epoch_ms) VALUES (?, ?)";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setLong(2, epochMillis(expiresAt, "expiresAt"));
            if (statement.executeUpdate() != 1) {
                throw new SessionRepositoryException("JDBC session insert did not create exactly one row");
            }
        }
    }

    private Optional<Object> value(String sessionId, String key) {
        validateKey(key);
        var sql = "SELECT encoded_value FROM " + valuesTable + " WHERE session_id = ? AND value_name = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setString(2, key);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                var decoded = decode(result.getString(1));
                if (result.next()) {
                    throw new SessionRepositoryException("JDBC session value lookup returned duplicate rows");
                }
                return Optional.of(decoded);
            }
        } catch (SQLException exception) {
            throw failure("read session value", exception);
        }
    }

    private void putValue(String sessionId, String key, Object value) {
        validateKey(key);
        if (value == null) {
            removeValue(sessionId, key);
            return;
        }
        var encoded = Objects.requireNonNull(codec.encode(value), "Session value codec result");
        if (encoded.length() > MAX_ENCODED_VALUE_LENGTH) {
            throw new IllegalArgumentException("Encoded JDBC session value exceeds "
                    + MAX_ENCODED_VALUE_LENGTH + " characters");
        }
        if (updateValue(sessionId, key, encoded)) {
            return;
        }
        try {
            insertValue(sessionId, key, encoded);
        } catch (SessionRepositoryException exception) {
            if (!constraintViolation(exception.getCause()) || !updateValue(sessionId, key, encoded)) {
                throw exception;
            }
        }
    }

    private boolean updateValue(String sessionId, String key, String encoded) {
        var sql = "UPDATE " + valuesTable + " SET encoded_value = ? WHERE session_id = ? AND value_name = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, encoded);
            statement.setString(2, sessionId);
            statement.setString(3, key);
            return changed(statement.executeUpdate(), "update value");
        } catch (SQLException exception) {
            throw failure("update session value", exception);
        }
    }

    private void insertValue(String sessionId, String key, String encoded) {
        var sql = "INSERT INTO " + valuesTable
                + " (session_id, value_name, encoded_value) VALUES (?, ?, ?)";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setString(2, key);
            statement.setString(3, encoded);
            if (statement.executeUpdate() != 1) {
                throw new SessionRepositoryException("JDBC session value insert did not create exactly one row");
            }
        } catch (SQLException exception) {
            throw failure("insert session value", exception);
        }
    }

    private void removeValue(String sessionId, String key) {
        validateKey(key);
        var sql = "DELETE FROM " + valuesTable + " WHERE session_id = ? AND value_name = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setString(2, key);
            changed(statement.executeUpdate(), "remove value");
        } catch (SQLException exception) {
            throw failure("remove session value", exception);
        }
    }

    private Map<String, Object> values(String sessionId) {
        var sql = "SELECT value_name, encoded_value FROM " + valuesTable + " WHERE session_id = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (var result = statement.executeQuery()) {
                var values = new LinkedHashMap<String, Object>();
                while (result.next()) {
                    var key = result.getString(1);
                    if (values.put(key, decode(result.getString(2))) != null) {
                        throw new SessionRepositoryException("JDBC session snapshot returned duplicate keys");
                    }
                }
                return Map.copyOf(values);
            }
        } catch (SQLException exception) {
            throw failure("snapshot session values", exception);
        }
    }

    private Object decode(String encoded) {
        try {
            return Objects.requireNonNull(codec.decode(encoded), "Session value codec result");
        } catch (RuntimeException exception) {
            if (exception instanceof SessionRepositoryException repositoryException) {
                throw repositoryException;
            }
            throw new SessionRepositoryException("Could not decode JDBC session value", exception);
        }
    }

    private final class JdbcSession extends Session {
        private JdbcSession(String id) {
            super(id);
        }

        @Override
        public Optional<Object> get(String key) {
            return value(id(), key);
        }

        @Override
        public void put(String key, Object value) {
            putValue(id(), key, value);
        }

        @Override
        public void remove(String key) {
            removeValue(id(), key);
        }

        @Override
        public Map<String, Object> snapshot() {
            return values(id());
        }
    }

    private static boolean changed(int rows, String operation) {
        if (rows < 0 || rows > 1) {
            throw new SessionRepositoryException("JDBC session " + operation + " changed an invalid row count");
        }
        return rows == 1;
    }

    private static void validateWindow(Instant accessedAt, Instant expiresAt) {
        Objects.requireNonNull(accessedAt, "accessedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        epochMillis(accessedAt, "accessedAt");
        epochMillis(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(accessedAt)) {
            throw new IllegalArgumentException("New session expiry must be after access time");
        }
    }

    private static void validateKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "JDBC session value key must be nonblank, control-free, and at most 255 characters");
        }
    }

    private static String validateTable(String table) {
        Objects.requireNonNull(table, "table");
        if (!IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException(
                    "Session table must be an unquoted SQL identifier of at most 40 characters");
        }
        return table;
    }

    private static long epochMillis(Instant instant, String name) {
        try {
            return instant.toEpochMilli();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is outside the supported epoch-millisecond range", exception);
        }
    }

    private static String token() {
        var bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constraintViolation(Throwable failure) {
        if (!(failure instanceof SQLException sqlException)) {
            return false;
        }
        var state = sqlException.getSQLState();
        return sqlException instanceof SQLIntegrityConstraintViolationException
                || state != null && state.startsWith("23");
    }

    private static void rollback(Connection connection, SQLException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException exception) {
            throw failure("restore connection state", exception);
        }
    }

    private static SessionRepositoryException failure(String operation, SQLException cause) {
        return new SessionRepositoryException("JDBC session repository could not " + operation, cause);
    }
}
