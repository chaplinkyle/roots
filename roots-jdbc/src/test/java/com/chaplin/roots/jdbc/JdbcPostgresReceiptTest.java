package com.chaplin.roots.jdbc;

import com.chaplin.roots.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration with a dedicated real PostgreSQL database, never an H2 mode. */
@EnabledIfSystemProperty(named = "roots.postgres.tests", matches = "true")
final class JdbcPostgresReceiptTest {
    private static final String SCHEMA = "roots_receipts_" + UUID.randomUUID().toString().replace("-", "");
    private static final String HASH = "c".repeat(64);
    private static DataSource source;
    private static boolean created;

    @BeforeAll
    static void setup() throws Exception {
        try (var connection = connect(); var statement = connection.createStatement()) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertEquals("roots_receipts_test", connection.getCatalog(), "Use a dedicated roots_receipts_test database");
            statement.executeUpdate("CREATE SCHEMA " + SCHEMA);
            created = true;
        }
        source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) throw new UnsupportedOperationException(method.getName());
                    var connection = connect();
                    connection.setSchema(SCHEMA);
                    return connection;
                });
        JdbcIdempotencyStore.createSchema(source);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE business (name VARCHAR(100) PRIMARY KEY)");
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (created) {
            try (var connection = connect(); var statement = connection.createStatement()) {
                statement.executeUpdate("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
        }
    }

    @Test
    void parallelNodesCommitOnceOnPostgresAndRetainTheResponse() throws Exception {
        var first = new JdbcIdempotencyStore(source);
        var second = new JdbcIdempotencyStore(source);
        var calls = new AtomicInteger();
        var jobs = new ArrayList<Callable<Response>>();
        for (int index = 0; index < 64; index++) {
            var store = index % 2 == 0 ? first : second;
            jobs.add(() -> store.execute("pg:user:create", "concurrent", HASH, Duration.ofHours(1), connection -> {
                calls.incrementAndGet();
                insert(connection, "concurrent");
                return Response.json(201, "{\"created\":true}");
            }));
        }
        int originals = 0;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var future : executor.invokeAll(jobs)) {
                var result = future.get(15, TimeUnit.SECONDS);
                assertEquals(201, result.status());
                assertEquals("{\"created\":true}", result.bodyText());
                if (result.headers().get("Idempotency-Replayed").equals(List.of("false"))) originals++;
            }
        }
        assertEquals(1, originals);
        assertEquals(1, calls.get());
        assertEquals(1, count("concurrent"));
        assertEquals(List.of("true"), new JdbcIdempotencyStore(source).execute("pg:user:create", "concurrent", HASH,
                Duration.ofHours(1), connection -> { throw new AssertionError(); }).headers().get("Idempotency-Replayed"));
    }

    @Test
    void terminatingTheDatabaseSessionRollsBackReceiptAndBusinessWrite() throws Exception {
        var store = new JdbcIdempotencyStore(source);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pid = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> assertThrows(SQLException.class, () ->
                    store.execute("pg:user:create", "terminated", HASH, Duration.ofHours(1), connection -> {
                        insert(connection, "terminated");
                        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                            assertTrue(rows.next()); pid.set(rows.getInt(1));
                        }
                        entered.countDown();
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                        return Response.text(201, "created");
                    })));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                try (var connection = source.getConnection(); var terminate = connection.prepareStatement("SELECT pg_terminate_backend(?)")) {
                    terminate.setInt(1, pid.get());
                    try (var rows = terminate.executeQuery()) { assertTrue(rows.next()); assertTrue(rows.getBoolean(1)); }
                }
            } finally { release.countDown(); }
            result.get(10, TimeUnit.SECONDS);
        }
        assertEquals(0, count("terminated"));
        store.execute("pg:user:create", "terminated", HASH, Duration.ofHours(1), connection -> {
            insert(connection, "terminated"); return Response.text(201, "created");
        });
        assertEquals(1, count("terminated"));
    }

    @Test
    void aSameKeyWaitTimesOutWithoutInvokingTheSecondCallback() throws Exception {
        var slow = new JdbcIdempotencyStore(source);
        var bounded = new JdbcIdempotencyStore(source, JdbcIdempotencyStore.DEFAULT_TABLE, 1024, 1, Clock.systemUTC());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> slow.execute("pg:user:create", "timeout", HASH, Duration.ofHours(1), connection -> {
                insert(connection, "timeout"); entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return Response.text(201, "created");
            }));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> assertThrows(SQLException.class, () -> bounded.execute(
                        "pg:user:create", "timeout", HASH, Duration.ofHours(1), connection -> { throw new AssertionError(); })));
                assertEquals("57014", second.get(8, TimeUnit.SECONDS).getSQLState());
            } finally { release.countDown(); }
            assertEquals(201, first.get(10, TimeUnit.SECONDS).status());
        }
        assertEquals(1, count("timeout"));
        assertEquals(List.of("true"), bounded.execute("pg:user:create", "timeout", HASH, Duration.ofHours(1),
                connection -> { throw new AssertionError(); }).headers().get("Idempotency-Replayed"));
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(Objects.requireNonNull(System.getenv("ROOTS_TEST_POSTGRES_URL")),
                Objects.requireNonNull(System.getenv("ROOTS_TEST_POSTGRES_USER")),
                Objects.requireNonNull(System.getenv("ROOTS_TEST_POSTGRES_PASSWORD")));
    }
    private static void insert(Connection connection, String name) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO business (name) VALUES (?)")) {
            statement.setString(1, name); statement.executeUpdate();
        }
    }
    private static int count(String name) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement("SELECT COUNT(*) FROM business WHERE name = ?")) {
            statement.setString(1, name);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
        }
    }
}
