package com.chaplin.roots.jdbc;

import com.chaplin.roots.Response;
import com.chaplin.roots.ResponseCookie;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class JdbcIdempotencyStoreTest {
    private static final String HASH = "a".repeat(64);
    private static final Duration DAY = Duration.ofDays(1);
    @TempDir Path temporary;

    @Test
    void twoNodesCommitBusinessWritesOnceAndReplayAcrossNewInstances() throws Exception {
        var source = database();
        initialize(source);
        var first = new JdbcIdempotencyStore(source);
        var second = new JdbcIdempotencyStore(source);
        var invocations = new AtomicInteger();
        var tasks = new ArrayList<Callable<Response>>();
        for (int index = 0; index < 64; index++) {
            var store = index % 2 == 0 ? first : second;
            tasks.add(() -> store.execute("tenant:user:create", "request-1", HASH, DAY, connection -> {
                invocations.incrementAndGet();
                increment(connection);
                return Response.json(201, "{\"created\":true}").withHeader("X-Values", "one")
                        .withAddedHeader("X-Values", "two");
            }));
        }
        int originals = 0;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var future : executor.invokeAll(tasks)) {
                var response = future.get(15, TimeUnit.SECONDS);
                assertEquals(201, response.status());
                assertEquals("{\"created\":true}", response.bodyText());
                assertEquals(List.of("one", "two"), response.headers().get("X-Values"));
                if (response.headers().get("Idempotency-Replayed").equals(List.of("false"))) originals++;
            }
        }
        assertEquals(1, originals);
        assertEquals(1, invocations.get());
        assertEquals(1, count(source));
        var replay = new JdbcIdempotencyStore(source).execute("tenant:user:create", "request-1", HASH, DAY,
                connection -> { throw new AssertionError("Committed work must not run again"); });
        assertEquals(List.of("true"), replay.headers().get("Idempotency-Replayed"));
        assertEquals(422, first.execute("tenant:user:create", "request-1", "b".repeat(64), DAY,
                connection -> { throw new AssertionError(); }).status());
        first.execute("other-tenant:user:create", "request-1", HASH, DAY, JdbcIdempotencyStoreTest::write);
        assertEquals(2, count(source));
    }

    @Test
    void differentKeysDoNotHoldAGlobalStoreLock() throws Exception {
        var source = database(); initialize(source);
        var store = new JdbcIdempotencyStore(source);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> store.execute("scope", "one", HASH, DAY, connection -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return Response.text(200, "one");
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals("two", executor.submit(() -> store.execute("scope", "two", HASH, DAY,
                        connection -> Response.text(200, "two"))).get(3, TimeUnit.SECONDS).bodyText());
            } finally { release.countDown(); }
            assertEquals("one", first.get(5, TimeUnit.SECONDS).bodyText());
        }
    }

    @Test
    void callbackAndEncodingFailuresRollBackBothBusinessDataAndReceipts() throws Exception {
        var source = database(); initialize(source);
        var store = new JdbcIdempotencyStore(source, JdbcIdempotencyStore.DEFAULT_TABLE, 20, 2, Clock.systemUTC());
        assertThrows(IllegalStateException.class, () -> store.execute("scope", "failure", HASH, DAY, connection -> {
            increment(connection); throw new IllegalStateException("business failure");
        }));
        assertEquals(0, count(source));
        assertThrows(IllegalArgumentException.class, () -> store.execute("scope", "failure", HASH, DAY, connection -> {
            increment(connection); return Response.text(200, "x".repeat(21));
        }));
        assertEquals(0, count(source));
        assertThrows(IllegalArgumentException.class, () -> store.execute("scope", "failure", HASH, DAY, connection -> {
            increment(connection); return Response.text(200, "ok").withCookie(ResponseCookie.builder("secret", "value").build());
        }));
        assertEquals(0, count(source));
        store.execute("scope", "failure", HASH, DAY, JdbcIdempotencyStoreTest::write);
        assertEquals(1, count(source));
    }

    @Test
    void lostCommitAcknowledgementReplaysWithoutRepeatingTheBusinessWrite() throws Exception {
        var source = database(); initialize(source);
        var commitLost = new AtomicBoolean();
        var faulting = intercept(source, (connection, method, args) -> {
            var result = invoke(connection, method, args);
            if (method.getName().equals("commit") && commitLost.compareAndSet(false, true)) {
                throw new SQLException("Connection lost after commit", "08006");
            }
            return result;
        });
        assertThrows(SQLException.class, () -> new JdbcIdempotencyStore(faulting)
                .execute("scope", "ambiguous", HASH, DAY, JdbcIdempotencyStoreTest::write));
        assertEquals(1, count(source));
        assertEquals(List.of("true"), new JdbcIdempotencyStore(source)
                .execute("scope", "ambiguous", HASH, DAY, connection -> { throw new AssertionError(); })
                .headers().get("Idempotency-Replayed"));
    }

    @Test
    void failureBeforeCommitRollsBackAndRetryCanPerformTheWrite() throws Exception {
        var source = database(); initialize(source);
        var faulting = intercept(source, (connection, method, args) -> {
            if (method.getName().equals("commit")) throw new SQLException("Commit not sent", "08006");
            return invoke(connection, method, args);
        });
        assertThrows(SQLException.class, () -> new JdbcIdempotencyStore(faulting)
                .execute("scope", "uncommitted", HASH, DAY, JdbcIdempotencyStoreTest::write));
        assertEquals(0, count(source));
        new JdbcIdempotencyStore(source).execute("scope", "uncommitted", HASH, DAY, JdbcIdempotencyStoreTest::write);
        assertEquals(1, count(source));
    }

    @Test
    void receiptSurvivesClosingAFileDatabaseAndReopeningIt() throws Exception {
        var source = source("jdbc:h2:file:" + temporary.resolve("receipts").toAbsolutePath());
        initialize(source);
        new JdbcIdempotencyStore(source).execute("scope", "durable", HASH, DAY, JdbcIdempotencyStoreTest::write);
        var reopened = source(source.getURL());
        assertEquals(1, count(reopened));
        assertEquals(List.of("true"), new JdbcIdempotencyStore(reopened)
                .execute("scope", "durable", HASH, DAY, connection -> { throw new AssertionError(); })
                .headers().get("Idempotency-Replayed"));
    }

    @Test
    void expiryIsMeasuredAfterWorkAndCleanupIsBounded() throws Exception {
        var source = database(); initialize(source);
        var clock = new TestClock();
        var store = new JdbcIdempotencyStore(source, JdbcIdempotencyStore.DEFAULT_TABLE, 1024, 2, clock);
        store.execute("scope", "one", HASH, Duration.ofSeconds(1), connection -> {
            clock.now = clock.now.plusSeconds(30); return write(connection);
        });
        assertEquals(0, store.deleteExpired(clock.instant(), 1));
        assertEquals(List.of("true"), store.execute("scope", "one", HASH, DAY,
                connection -> { throw new AssertionError(); }).headers().get("Idempotency-Replayed"));
        clock.now = clock.now.plusSeconds(2);
        // Expired key reuse works without requiring the maintenance job first.
        store.execute("scope", "one", "b".repeat(64), Duration.ofSeconds(1), JdbcIdempotencyStoreTest::write);
        store.execute("scope", "two", HASH, Duration.ofSeconds(1), JdbcIdempotencyStoreTest::write);
        assertEquals(3, count(source));
        clock.now = clock.now.plusSeconds(2);
        assertEquals(1, store.deleteExpired(clock.instant(), 1));
        assertEquals(1, store.deleteExpired(clock.instant(), 1));
        assertEquals(0, store.deleteExpired(clock.instant(), 1));
    }

    @Test
    void corruptOrIncompleteReceiptsNeverExecuteBusinessWorkAgain() throws Exception {
        var source = database(); initialize(source);
        var store = new JdbcIdempotencyStore(source);
        store.execute("scope", "corrupt", HASH, DAY, JdbcIdempotencyStoreTest::write);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE roots_operation_receipts SET response_status = 0");
            assertEquals(409, store.execute("scope", "corrupt", HASH, DAY,
                    unused -> { throw new AssertionError(); }).status());
            statement.executeUpdate("UPDATE roots_operation_receipts SET response_status = 201, response_headers = '!'");
            assertThrows(java.io.IOException.class, () -> store.execute("scope", "corrupt", HASH, DAY,
                    unused -> { throw new AssertionError(); }));
        }
        assertEquals(1, count(source));
    }

    @Test
    void rollbackFailureAbortsTheConnectionAndRetainsTheOriginalError() throws Exception {
        var source = database(); initialize(source);
        var aborted = new AtomicBoolean();
        var faulting = intercept(source, (connection, method, args) -> {
            if (method.getName().equals("rollback")) throw new SQLException("rollback unavailable");
            if (method.getName().equals("abort")) {
                aborted.set(true); connection.close(); return null;
            }
            if (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) {
                throw new AssertionError("Never enable auto-commit after a failed rollback");
            }
            return invoke(connection, method, args);
        });
        var error = assertThrows(IllegalStateException.class, () -> new JdbcIdempotencyStore(faulting)
                .execute("scope", "abort", HASH, DAY, connection -> {
                    increment(connection); throw new IllegalStateException("original business failure");
                }));
        assertEquals("original business failure", error.getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertTrue(aborted.get());
        assertEquals(0, count(source));
    }

    @Test
    void aConnectionResetFailureDoesNotHideWhetherTheTransactionCommitted() throws Exception {
        var source = database(); initialize(source);
        var isolationCalls = new AtomicInteger();
        var faulting = intercept(source, (connection, method, args) -> {
            if (method.getName().equals("setTransactionIsolation") && isolationCalls.incrementAndGet() == 2) {
                throw new SQLException("Pool reset unavailable");
            }
            return invoke(connection, method, args);
        });
        assertThrows(SQLException.class, () -> new JdbcIdempotencyStore(faulting)
                .execute("scope", "reset", HASH, DAY, JdbcIdempotencyStoreTest::write));
        assertEquals(1, count(source));
        assertEquals(List.of("true"), new JdbcIdempotencyStore(source).execute("scope", "reset", HASH, DAY,
                connection -> { throw new AssertionError(); }).headers().get("Idempotency-Replayed"));
    }

    @Test
    void customTablesAndIndependentConnectionRequirementsAreEnforced() throws Exception {
        var source = database(); initialize(source);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            for (var sql : JdbcIdempotencyStore.schemaStatements("custom_receipts")) statement.executeUpdate(sql);
        }
        var store = new JdbcIdempotencyStore(source, "custom_receipts", 1024, 1, Clock.systemUTC());
        store.execute("scope", "custom", HASH, DAY, JdbcIdempotencyStoreTest::write);
        var managed = intercept(source, (connection, method, args) -> {
            if (method.getName().equals("getAutoCommit")) return false;
            return invoke(connection, method, args);
        });
        assertThrows(SQLException.class, () -> new JdbcIdempotencyStore(managed).execute("scope", "bound", HASH, DAY,
                connection -> { throw new AssertionError(); }));
        assertEquals(1, count(source));
    }

    @Test
    void invalidInputsFailBeforeBusinessWorkAndDoNotPermitSqlIdentifiers() throws Exception {
        var source = database(); initialize(source);
        var store = new JdbcIdempotencyStore(source);
        assertThrows(IllegalArgumentException.class, () -> JdbcIdempotencyStore.schemaStatements("receipts; DROP TABLE business"));
        assertThrows(IllegalArgumentException.class, () -> new JdbcIdempotencyStore(source, "ok", 0, 1, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new JdbcIdempotencyStore(source, "ok", 786433, 1, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new JdbcIdempotencyStore(source, "ok", 1, 0, Clock.systemUTC()));
        for (var scope : List.of("", "\n", "s".repeat(513))) {
            assertThrows(IllegalArgumentException.class, () -> store.execute(scope, "key", HASH, DAY,
                    connection -> { throw new AssertionError(); }));
        }
        for (var key : List.of("", "space key", "é", "k".repeat(201))) {
            assertThrows(IllegalArgumentException.class, () -> store.execute("scope", key, HASH, DAY,
                    connection -> { throw new AssertionError(); }));
        }
        assertThrows(IllegalArgumentException.class, () -> store.execute("scope", "key", "bad", DAY,
                connection -> { throw new AssertionError(); }));
        for (var retention : List.of(Duration.ZERO, Duration.ofDays(8))) {
            assertThrows(IllegalArgumentException.class, () -> store.execute("scope", "key", HASH, retention,
                    connection -> { throw new AssertionError(); }));
        }
        assertThrows(IllegalArgumentException.class, () -> store.deleteExpired(Instant.now(), 0));
        assertThrows(IllegalArgumentException.class, () -> store.deleteExpired(Instant.now(), 10001));
        assertEquals(0, count(source));
    }

    private static JdbcDataSource database() { return source("jdbc:h2:mem:receipts_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"); }
    private static JdbcDataSource source(String url) { var source = new JdbcDataSource(); source.setURL(url); return source; }
    private static void initialize(DataSource source) throws SQLException {
        JdbcIdempotencyStore.createSchema(source);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE business (id INTEGER PRIMARY KEY, writes INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO business VALUES (1, 0)");
        }
    }
    private static void increment(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) { statement.executeUpdate("UPDATE business SET writes = writes + 1 WHERE id = 1"); }
    }
    private static Response write(Connection connection) throws SQLException { increment(connection); return Response.text(201, "created"); }
    private static int count(DataSource source) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT writes FROM business WHERE id = 1")) {
            assertTrue(rows.next()); return rows.getInt(1);
        }
    }
    private static DataSource intercept(DataSource source, ConnectionInvocation invocation) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    var result = invoke(source, method, args);
                    if (result instanceof Connection connection) {
                        return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                                (wrapped, operation, parameters) -> invocation.call(connection, operation, parameters));
                    }
                    return result;
                });
    }
    private static Object invoke(Object object, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(object, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    @FunctionalInterface private interface ConnectionInvocation {
        Object call(Connection connection, java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-06T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
