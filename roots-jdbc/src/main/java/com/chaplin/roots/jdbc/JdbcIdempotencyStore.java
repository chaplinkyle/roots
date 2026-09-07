package com.chaplin.roots.jdbc;

import com.chaplin.roots.ProblemDetail;
import com.chaplin.roots.Response;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Durable receipts committed atomically with business SQL on the supplied connection.
 * Authenticate and authorize every invocation and include tenant, principal, and
 * operation in an unambiguous scope. The callback must use this connection for all
 * writes, must not manage its transaction/lifecycle, and must not perform external
 * effects. Use a transactional outbox for effects outside the database.
 *
 * <p>This API intentionally differs from the process-local core store: its callback
 * receives the transaction connection. Different keys do not share a global lock.
 * Same-key contenders serialize in the database, subject to statement timeouts.
 * An exception rolls back SQL and the receipt; an ambiguous commit is resolved by
 * retrying the same key. Keys can execute again after retention expires.</p>
 *
 * <p>The application owns the pool, migrations, database durability/replication,
 * backups, and periodic bounded expiry cleanup. This store uses read-committed
 * row locking and SQLState 23505 collision handling, tested with H2 and PostgreSQL.
 * It does not serialize Java objects or automatically make live actions durable.</p>
 */
public final class JdbcIdempotencyStore {
    /** Default receipt table. */
    public static final String DEFAULT_TABLE = "roots_operation_receipts";
    private final DataSource dataSource;
    private final String table;
    private final int maxResponseBytes;
    private final int statementTimeoutSeconds;
    private final Clock clock;

    /** Uses the default table, 128 KiB response bodies, and ten-second receipt statements.
     * @param dataSource application-owned source of independent auto-commit connections */
    public JdbcIdempotencyStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE, 128 * 1024, 10, Clock.systemUTC());
    }

    /** Creates an explicitly configured durable receipt store.
     * @param dataSource application-owned source of independent auto-commit connections
     * @param table unqualified SQL identifier, up to 40 characters
     * @param maxResponseBytes body limit, 1 through 786432 bytes
     * @param statementTimeoutSeconds receipt statement timeout, 1 through 300 seconds;
     *                               callback statements require their own timeout
     * @param clock shared UTC time source; cluster nodes must have synchronized clocks */
    public JdbcIdempotencyStore(DataSource dataSource, String table, int maxResponseBytes,
            int statementTimeoutSeconds, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.table = tableName(table);
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxResponseBytes < 1 || maxResponseBytes > 786432) {
            throw new IllegalArgumentException("Receipt response limit must be 1 to 786432 bytes");
        }
        if (statementTimeoutSeconds < 1 || statementTimeoutSeconds > 300) {
            throw new IllegalArgumentException("Receipt statement timeout must be 1 to 300 seconds");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.statementTimeoutSeconds = statementTimeoutSeconds;
    }

    /** Returns DDL for the default table and expiry index.
     * @return ordered schema statements */
    public static List<String> schemaStatements() { return schemaStatements(DEFAULT_TABLE); }

    /** Returns DDL for migration tooling. Do not run concurrently during startup.
     * @param table unqualified SQL identifier
     * @return ordered schema statements */
    public static List<String> schemaStatements(String table) {
        table = tableName(table);
        return List.of("CREATE TABLE " + table + " (receipt_id CHAR(64) PRIMARY KEY, "
                + "fingerprint CHAR(64) NOT NULL, expires_at_epoch_ms BIGINT NOT NULL, "
                + "response_status INTEGER NOT NULL, response_headers VARCHAR(32768), "
                + "response_body VARCHAR(1048576))",
                "CREATE INDEX " + table + "_expiry_idx ON " + table + " (expires_at_epoch_ms)");
    }

    /** Creates the default schema once for tests or first-run tooling.
     * @param dataSource target database
     * @throws SQLException on DDL failure */
    public static void createSchema(DataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (var sql : schemaStatements()) statement.executeUpdate(sql);
        }
    }

    /**
     * Executes transactional SQL or replays a committed response. Different content
     * under an unexpired key returns 422. A returned response commits; throwing rolls
     * back. Use the same key to resolve a lost response/ambiguous commit. No callback
     * that has begun execution is automatically retried by this method.
     *
     * @param scope tenant/principal/operation scope, 1 to 512 printable characters
     * @param key client key, 1 to 200 visible ASCII characters
     * @param fingerprint 64 lowercase SHA-256 hexadecimal characters
     * @param retention deduplication window after completion, one second to seven days
     * @param work business SQL using only the provided connection
     * @return original/replayed buffered response, 422 conflict, or 409 for an incomplete receipt
     * @throws Exception on callback, response encoding, database, or commit failure */
    public Response execute(String scope, String key, String fingerprint, Duration retention,
            TransactionalWork work) throws Exception {
        var id = receiptId(scope, key);
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(work, "work");
        if (!fingerprint.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Fingerprint must be SHA-256");
        if (retention.compareTo(Duration.ofSeconds(1)) < 0 || retention.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Receipt retention must be one second to seven days");
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return transaction(connection -> {
                    var existing = existing(connection, id, fingerprint);
                    if (existing != null) return existing;
                    try (var claim = prepare(connection, "INSERT INTO " + table
                            + " (receipt_id, fingerprint, expires_at_epoch_ms, response_status) VALUES (?, ?, ?, 0)")) {
                        claim.setString(1, id);
                        claim.setString(2, fingerprint);
                        claim.setLong(3, Long.MAX_VALUE);
                        try { claim.executeUpdate(); }
                        catch (SQLException collision) {
                            if ("23505".equals(collision.getSQLState())) throw new ClaimCollision(collision);
                            throw collision;
                        }
                    }
                    var response = Objects.requireNonNull(work.run(connection), "transaction response");
                    if (connection.getAutoCommit()) {
                        throw new IllegalStateException("Receipt callback must not manage the connection transaction");
                    }
                    var encoded = JdbcReceiptResponse.encode(response, maxResponseBytes);
                    try (var save = prepare(connection, "UPDATE " + table
                            + " SET expires_at_epoch_ms = ?, response_status = ?, response_headers = ?, response_body = ?"
                            + " WHERE receipt_id = ? AND response_status = 0")) {
                        save.setLong(1, clock.instant().plus(retention).toEpochMilli());
                        save.setInt(2, response.status());
                        save.setString(3, encoded.headers());
                        save.setString(4, encoded.body());
                        save.setString(5, id);
                        if (save.executeUpdate() != 1) throw new SQLException("Receipt ownership was lost");
                    }
                    return response.withHeader("Idempotency-Replayed", "false");
                });
            } catch (ClaimCollision collision) {
                // A competing transaction inserted after our read. Its commit is
                // visible only in a fresh transaction. No application work ran here.
                if (attempt == 3) throw new SQLException("Concurrent receipt claim did not settle", collision);
            }
        }
        throw new AssertionError("Receipt attempt loop exhausted");
    }

    /** Deletes at most one bounded batch of expired receipts. Run periodically.
     * Expiry permits a key to execute again; choose retention above the retry window.
     * @param now expiry cutoff
     * @param maximum maximum deletions, 1 through 10000
     * @return number deleted
     * @throws Exception on database or transaction failure */
    public int deleteExpired(Instant now, int maximum) throws Exception {
        Objects.requireNonNull(now, "now");
        if (maximum < 1 || maximum > 10000) throw new IllegalArgumentException("Cleanup batch must be 1 to 10000");
        return transaction(connection -> {
            var ids = new ArrayList<String>();
            try (var select = prepare(connection, "SELECT receipt_id FROM " + table
                    + " WHERE expires_at_epoch_ms <= ? ORDER BY expires_at_epoch_ms")) {
                select.setLong(1, now.toEpochMilli());
                select.setMaxRows(maximum);
                try (var rows = select.executeQuery()) { while (rows.next()) ids.add(rows.getString(1)); }
            }
            int deleted = 0;
            try (var remove = prepare(connection, "DELETE FROM " + table
                    + " WHERE receipt_id = ? AND expires_at_epoch_ms <= ?")) {
                for (var id : ids) {
                    remove.setString(1, id);
                    remove.setLong(2, now.toEpochMilli());
                    deleted += remove.executeUpdate();
                }
            }
            return deleted;
        });
    }

    private Response existing(Connection connection, String id, String fingerprint) throws Exception {
        try (var select = prepare(connection, "SELECT fingerprint, expires_at_epoch_ms, response_status,"
                + " response_headers, response_body FROM " + table + " WHERE receipt_id = ? FOR UPDATE")) {
            select.setString(1, id);
            try (var row = select.executeQuery()) {
                if (!row.next()) return null;
                if (row.getLong(2) > clock.millis()) {
                    if (!fingerprint.equals(row.getString(1))) {
                        return problem(422, "key-reused", "Use a new idempotency key for a different request.");
                    }
                    if (row.getInt(3) == 0) {
                        return problem(409, "outcome-unknown", "An incomplete receipt requires operator reconciliation.");
                    }
                    return JdbcReceiptResponse.decode(row.getInt(3), row.getString(4), row.getString(5),
                            maxResponseBytes).withHeader("Idempotency-Replayed", "true");
                }
            }
        }
        try (var delete = prepare(connection, "DELETE FROM " + table + " WHERE receipt_id = ?")) {
            delete.setString(1, id);
            delete.executeUpdate();
        }
        return null;
    }

    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try { statement.setQueryTimeout(statementTimeoutSeconds); return statement; }
        catch (SQLException failure) { statement.close(); throw failure; }
    }

    private <T> T transaction(SqlTransaction<T> work) throws Exception {
        try (var connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) throw new SQLException("Receipt store requires an independent auto-commit connection");
            int isolation = connection.getTransactionIsolation();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            boolean ended = false;
            Throwable failure = null;
            try {
                var value = work.run(connection);
                connection.commit();
                ended = true;
                return value;
            } catch (Exception | Error exception) {
                failure = exception;
                try { connection.rollback(); ended = true; }
                catch (SQLException rollback) {
                    exception.addSuppressed(rollback);
                    try { connection.abort(Runnable::run); }
                    catch (SQLException abort) { exception.addSuppressed(abort); }
                }
                throw exception;
            } finally {
                if (ended) {
                    try { connection.setTransactionIsolation(isolation); connection.setAutoCommit(true); }
                    catch (SQLException restore) {
                        if (failure == null) throw restore;
                        failure.addSuppressed(restore);
                    }
                }
            }
        }
    }

    private static String receiptId(String scope, String key) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        if (scope.isBlank() || scope.length() > 512 || scope.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Scope must be printable and at most 512 characters");
        }
        if (key.isEmpty() || key.length() > 200 || key.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("Key must contain 1 to 200 visible ASCII characters");
        }
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (var part : List.of(scope, key)) {
                hash.update((byte) (part.length() >>> 8));
                hash.update((byte) part.length());
                for (int index = 0; index < part.length(); index++) {
                    char value = part.charAt(index);
                    hash.update((byte) (value >>> 8)); hash.update((byte) value);
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String tableName(String table) {
        Objects.requireNonNull(table, "table");
        if (!table.matches("[A-Za-z][A-Za-z0-9_]{0,39}")) throw new IllegalArgumentException("Invalid receipt table identifier");
        return table;
    }

    private static Response problem(int status, String code, String detail) {
        return ProblemDetail.of(URI.create("urn:roots:problem:" + code), status, "Idempotency conflict", detail).response();
    }

    /** Business SQL sharing the receipt transaction. Never close/commit/rollback the connection. */
    @FunctionalInterface
    public interface TransactionalWork {
        /** Runs SQL using this transaction; external effects require an outbox.
         * @param connection transaction-owned connection
         * @return final buffered response; returning commits, throwing rolls back
         * @throws Exception to roll back business writes and the receipt */
        Response run(Connection connection) throws Exception;
    }

    @FunctionalInterface
    private interface SqlTransaction<T> { T run(Connection connection) throws Exception; }
    private static final class ClaimCollision extends Exception {
        private ClaimCollision(SQLException cause) { super(cause); }
    }
}
