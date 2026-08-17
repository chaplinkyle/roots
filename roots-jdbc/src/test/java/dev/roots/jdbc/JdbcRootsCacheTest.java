package dev.roots.jdbc;

import dev.roots.CachePolicy;
import dev.roots.Roots;
import dev.roots.RootsCache;
import dev.roots.RootsConfig;
import dev.roots.jdbc.fixture.Application;
import dev.roots.jdbc.fixture.pages.Page;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcRootsCacheTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void sharesValuesTagsExpiryAndDiagnosticsAcrossAdapters() {
        var dataSource = dataSource();
        JdbcRootsCache.createSchema(dataSource);
        var clock = new AtomicLong(1_000);
        var first = cache(dataSource, clock, 10);
        var second = cache(dataSource, clock, 10);
        var loads = new AtomicInteger();
        var policy = CachePolicy.tagged(Duration.ofMillis(50), "customers", "tenant-7");

        assertEquals("value-1", first.get("customer-list", String.class, policy,
                () -> "value-" + loads.incrementAndGet()));
        assertEquals("value-1", second.get("customer-list", String.class, policy,
                () -> "unexpected"));
        assertEquals(1, loads.get());
        assertEquals(1, first.snapshot().entries());
        assertEquals(1, second.snapshot().hits());

        assertEquals(1, second.invalidateTag("tenant-7"));
        assertEquals(0, second.invalidateTag("tenant-7"));
        assertEquals("value-2", first.get("customer-list", String.class, policy,
                () -> "value-" + loads.incrementAndGet()));
        assertEquals(2, loads.get());

        clock.addAndGet(50);
        assertEquals("value-3", second.get("customer-list", String.class, policy,
                () -> "value-" + loads.incrementAndGet()));
        var snapshot = second.snapshot();
        assertEquals(1, snapshot.entries());
        assertEquals(1, snapshot.expirations());
        assertEquals(1, snapshot.invalidations());
        assertEquals(1, snapshot.misses());
        assertEquals(1, snapshot.loads());
        assertTrue(snapshot.enabled());
    }

    @Test
    void exactlyOneNodeLoadsAContendedDistributedMiss() throws Exception {
        var dataSource = dataSource();
        JdbcRootsCache.createSchema(dataSource);
        var first = new JdbcRootsCache(dataSource);
        var second = new JdbcRootsCache(dataSource);
        var loaderCalls = new AtomicInteger();
        var policy = CachePolicy.tagged(Duration.ofMinutes(1), "catalog");
        var tasks = new ArrayList<Callable<String>>();
        for (var index = 0; index < 200; index++) {
            var cache = index % 2 == 0 ? first : second;
            tasks.add(() -> cache.get("catalog", String.class, policy, () -> {
                loaderCalls.incrementAndGet();
                awaitCondition(() -> first.snapshot().misses() + second.snapshot().misses() == 200);
                return "shared";
            }));
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var result : executor.invokeAll(tasks)) {
                assertEquals("shared", result.get());
            }
        }

        assertEquals(1, loaderCalls.get());
        assertEquals(1, first.snapshot().entries());
        assertEquals(200, first.snapshot().misses() + second.snapshot().misses());
        assertEquals(199, first.snapshot().coalescedLoads() + second.snapshot().coalescedLoads());
        assertEquals(1, first.snapshot().loads() + second.snapshot().loads());
    }

    @Test
    void tagInvalidationDuringLoadPreventsStalePublication() throws Exception {
        var dataSource = dataSource();
        JdbcRootsCache.createSchema(dataSource);
        var owner = new JdbcRootsCache(dataSource);
        var invalidator = new JdbcRootsCache(dataSource);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var policy = CachePolicy.tagged(Duration.ofMinutes(1), "products");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stale = executor.submit(() -> owner.get("products", String.class, policy, () -> {
                entered.countDown();
                await(release);
                return "stale";
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, invalidator.invalidateTag("products"));
            release.countDown();
            assertEquals("stale", stale.get(5, TimeUnit.SECONDS));
        }

        assertEquals(0, owner.snapshot().entries());
        assertEquals("fresh", invalidator.get("products", String.class, policy, () -> "fresh"));
        assertEquals("fresh", owner.get("products", String.class, policy, () -> "unexpected"));
    }

    @Test
    void expiredLoadLeaseIsRecoveredWithoutAllowingLateOverwrite() throws Exception {
        var dataSource = dataSource();
        JdbcRootsCache.createSchema(dataSource);
        var clock = new AtomicLong(10_000);
        var abandoned = cache(dataSource, clock, 10, Duration.ofMillis(25));
        var recovery = cache(dataSource, clock, 10, Duration.ofMillis(25));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var late = executor.submit(() -> abandoned.get("lease", String.class, Duration.ofMinutes(1), () -> {
                entered.countDown();
                await(release);
                return "late";
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            clock.addAndGet(25);
            assertEquals("recovered", recovery.get("lease", String.class, Duration.ofMinutes(1),
                    () -> "recovered"));
            release.countDown();
            assertEquals("late", late.get(5, TimeUnit.SECONDS));
        }

        assertEquals("recovered", abandoned.get("lease", String.class, Duration.ofMinutes(1),
                () -> "unexpected"));
    }

    @Test
    void failedLoadsAndInterruptedWaitersDoNotPoisonTheKey() throws Exception {
        var dataSource = dataSource();
        JdbcRootsCache.createSchema(dataSource);
        var first = new JdbcRootsCache(dataSource);
        var second = new JdbcRootsCache(dataSource);
        var expected = new IllegalArgumentException("loader failed");
        assertEquals(expected, assertThrows(IllegalArgumentException.class,
                () -> first.get("failure", String.class, Duration.ofMinutes(1), () -> {
                    throw expected;
                })));
        assertEquals("recovered", second.get("failure", String.class, Duration.ofMinutes(1),
                () -> "recovered"));

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = executor.submit(() -> first.get("wait", String.class, Duration.ofMinutes(1), () -> {
                entered.countDown();
                await(release);
                return "done";
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            try {
                var interrupted = assertThrows(JdbcCacheException.class,
                        () -> second.get("wait", String.class, Duration.ofMinutes(1), () -> "wrong"));
                assertTrue(interrupted.getMessage().contains("Interrupted"));
            } finally {
                assertTrue(Thread.interrupted());
            }

            var interruptedBySleeper = new JdbcRootsCache(dataSource, JdbcCacheValueCodec.standard(),
                    JdbcRootsCache.DEFAULT_TABLE, RootsCache.DEFAULT_MAX_ENTRIES,
                    Duration.ofMinutes(5), Duration.ofNanos(1), System::currentTimeMillis,
                    ignored -> Thread.currentThread().interrupt());
            try {
                var interrupted = assertThrows(JdbcCacheException.class,
                        () -> interruptedBySleeper.get("wait", String.class,
                                Duration.ofMinutes(1), () -> "wrong"));
                assertTrue(interrupted.getMessage().contains("Interrupted"));
            } finally {
                assertTrue(Thread.interrupted());
            }
            release.countDown();
            assertEquals("done", owner.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void enforcesCapacityAndNeverImmediatelyEvictsThePublishedValue() {
        var dataSource = dataSource();
        JdbcRootsCache.createSchema(dataSource);
        var clock = new AtomicLong(1_000);
        var cache = cache(dataSource, clock, 2);

        assertEquals("one", cache.get("z-oldest", String.class, Duration.ofMinutes(1), () -> "one"));
        clock.incrementAndGet();
        assertEquals("two", cache.get("middle", String.class, Duration.ofMinutes(1), () -> "two"));
        clock.incrementAndGet();
        assertEquals("three", cache.get("a-newest", String.class, Duration.ofMinutes(1), () -> "three"));

        var snapshot = cache.snapshot();
        assertEquals(2, snapshot.entries());
        assertEquals(1, snapshot.evictions());
        assertEquals("three", cache.get("a-newest", String.class, Duration.ofMinutes(1), () -> "wrong"));
        assertEquals("reloaded", cache.get("z-oldest", String.class, Duration.ofMinutes(1), () -> "reloaded"));
        assertEquals(2, cache.snapshot().entries());
    }

    @Test
    void standardCodecIsSafeTypedAndRoundTripsEveryScalarFamily() {
        var codec = JdbcCacheValueCodec.standard();
        assertEquals("hello", roundTrip(codec, "hello", String.class));
        assertEquals(true, roundTrip(codec, true, Boolean.class));
        assertEquals(42, roundTrip(codec, 42, Integer.class));
        assertEquals(new BigDecimal("10.50"), roundTrip(codec, new BigDecimal("10.50"), BigDecimal.class));
        assertEquals(Instant.parse("2026-08-16T12:00:00Z"), roundTrip(codec,
                Instant.parse("2026-08-16T12:00:00Z"), Instant.class));
        var bytes = new byte[]{0, 1, -1};
        assertArrayEquals(bytes, roundTrip(codec, bytes, byte[].class));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new Object()));
        assertThrows(IllegalStateException.class, () -> codec.decode(codec.encode(12), String.class));
        assertThrows(NullPointerException.class, () -> codec.decode("int:1", null));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsWrongNullMalformedAndOversizedValuesWithoutCachingThem() {
        var dataSource = dataSource();
        JdbcRootsCache.createSchema(dataSource);
        var cache = new JdbcRootsCache(dataSource);

        assertThrows(NullPointerException.class,
                () -> cache.get("null", String.class, Duration.ofMinutes(1), () -> null));
        Supplier wrong = () -> 42;
        assertThrows(IllegalStateException.class,
                () -> cache.get("wrong", String.class, Duration.ofMinutes(1), wrong));
        assertThrows(IllegalArgumentException.class,
                () -> cache.get("unsupported", Object.class, Duration.ofMinutes(1), Object::new));

        var oversized = new JdbcRootsCache(dataSource, new JdbcCacheValueCodec() {
            @Override public String encode(Object value) { return "x".repeat(8193); }
            @Override public <T> T decode(String encoded, Class<T> type) { return type.cast(encoded); }
        });
        assertThrows(IllegalArgumentException.class,
                () -> oversized.get("large", String.class, Duration.ofMinutes(1), () -> "value"));

        assertEquals(0, cache.snapshot().entries());
        assertEquals("valid", cache.get("null", String.class, Duration.ofMinutes(1), () -> "valid"));
        assertThrows(IllegalStateException.class,
                () -> cache.get("null", Integer.class, Duration.ofMinutes(1), () -> 1));
    }

    @Test
    void validatesConfigurationKeysTagsAndSchemaBoundaries() {
        var dataSource = dataSource();
        assertThrows(NullPointerException.class, () -> new JdbcRootsCache(null));
        assertThrows(NullPointerException.class, () -> new JdbcRootsCache(dataSource, null));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcRootsCache(dataSource, JdbcCacheValueCodec.standard(), "cache; DROP TABLE users"));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcRootsCache(dataSource, JdbcCacheValueCodec.standard(), "cache", 0,
                        Duration.ofSeconds(1), Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcRootsCache(dataSource, JdbcCacheValueCodec.standard(), "cache", 1,
                        Duration.ZERO, Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcRootsCache(dataSource, JdbcCacheValueCodec.standard(), "cache", 1,
                        Duration.ofSeconds(1), Duration.ZERO));
        assertThrows(NullPointerException.class,
                () -> new JdbcRootsCache(dataSource, JdbcCacheValueCodec.standard(), "cache", 1,
                        null, Duration.ofMillis(1)));
        assertThrows(NullPointerException.class,
                () -> new JdbcRootsCache(dataSource, JdbcCacheValueCodec.standard(), "cache", 1,
                        Duration.ofSeconds(1), null));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcRootsCache(dataSource, JdbcCacheValueCodec.standard(), "cache", 1,
                        Duration.ofSeconds(-1), Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcRootsCache(dataSource, JdbcCacheValueCodec.standard(), "cache", 1,
                        Duration.ofSeconds(1), Duration.ofNanos(-1)));
        new JdbcRootsCache(dataSource, JdbcCacheValueCodec.standard(), "cache", 1,
                Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE));
        assertThrows(UnsupportedOperationException.class,
                () -> JdbcRootsCache.schemaStatements().add("unsafe"));
        assertEquals(8, JdbcRootsCache.schemaStatements("custom_cache").size());

        JdbcRootsCache.createSchema(dataSource);
        RootsCache cache = new JdbcRootsCache(dataSource);
        assertThrows(IllegalArgumentException.class,
                () -> cache.get(null, String.class, Duration.ofSeconds(1), () -> "x"));
        assertThrows(IllegalArgumentException.class,
                () -> cache.get(" ", String.class, Duration.ofSeconds(1), () -> "x"));
        assertThrows(IllegalArgumentException.class,
                () -> cache.get("x".repeat(1025), String.class, Duration.ofSeconds(1), () -> "x"));
        assertThrows(IllegalArgumentException.class,
                () -> cache.get("bad\nkey", String.class, Duration.ofSeconds(1), () -> "x"));
        assertThrows(IllegalArgumentException.class,
                () -> cache.get("bad\u007fkey", String.class, Duration.ofSeconds(1), () -> "x"));
        assertThrows(IllegalArgumentException.class, () -> cache.invalidate("\t"));
        assertThrows(IllegalArgumentException.class, () -> cache.invalidateTag(null));
        assertThrows(IllegalArgumentException.class, () -> cache.invalidateTag(" "));
        assertThrows(IllegalArgumentException.class, () -> cache.invalidateTag("bad\n"));
        assertThrows(IllegalArgumentException.class, () -> cache.invalidateTag("x".repeat(257)));
        assertThrows(IllegalArgumentException.class,
                () -> cache.get("key", String.class,
                        CachePolicy.tagged(Duration.ofSeconds(1), "bad\r"), () -> "x"));
    }

    @Test
    void saturatesExtremeDeadlinesAndRejectsAnInvalidClock() {
        var dataSource = dataSource();
        JdbcRootsCache.createSchema(dataSource);
        var nearMaximum = new AtomicLong(Long.MAX_VALUE - 2);
        var cache = new JdbcRootsCache(dataSource, JdbcCacheValueCodec.standard(),
                JdbcRootsCache.DEFAULT_TABLE, 2, Duration.ofMillis(10), Duration.ofNanos(1),
                nearMaximum::get, ignored -> Thread.yield());

        assertEquals("forever", cache.get("extreme", String.class,
                Duration.ofSeconds(Long.MAX_VALUE), () -> "forever"));
        assertEquals("tiny", cache.get("tiny", String.class,
                Duration.ofNanos(1), () -> "tiny"));

        var invalidClock = new JdbcRootsCache(dataSource, JdbcCacheValueCodec.standard(),
                "other_cache", 1, Duration.ofSeconds(1), Duration.ofMillis(1),
                () -> -1, ignored -> { });
        assertThrows(IllegalStateException.class,
                () -> invalidClock.get("key", String.class, Duration.ofSeconds(1), () -> "value"));
    }

    @Test
    void clearCountsDistinctEntryAndLoadKeysAndIsShared() throws Exception {
        var dataSource = dataSource();
        JdbcRootsCache.createSchema(dataSource);
        var first = new JdbcRootsCache(dataSource);
        var second = new JdbcRootsCache(dataSource);
        first.get("stored", String.class, Duration.ofMinutes(1), () -> "stored");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var loading = executor.submit(() -> first.get("loading", String.class, Duration.ofMinutes(1), () -> {
                entered.countDown();
                await(release);
                return "late";
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            second.clear();
            assertEquals(2, second.snapshot().invalidations());
            assertEquals(0, second.snapshot().entries());
            release.countDown();
            assertEquals("late", loading.get(5, TimeUnit.SECONDS));
        }

        assertEquals(0, first.snapshot().entries());
        second.clear();
        assertEquals(2, second.snapshot().invalidations());
        assertFalse(second.invalidate("missing"));
    }

    @Test
    void keyInvalidationRemovesStoredAndInFlightValues() throws Exception {
        var dataSource = dataSource();
        JdbcRootsCache.createSchema(dataSource);
        var owner = new JdbcRootsCache(dataSource);
        var invalidator = new JdbcRootsCache(dataSource);

        owner.get("stored-key", String.class, Duration.ofMinutes(1), () -> "old");
        assertTrue(invalidator.invalidate("stored-key"));
        assertEquals("new", owner.get("stored-key", String.class, Duration.ofMinutes(1), () -> "new"));

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var late = executor.submit(() -> owner.get("in-flight-key", String.class,
                    Duration.ofMinutes(1), () -> {
                        entered.countDown();
                        await(release);
                        return "late";
                    }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(invalidator.invalidate("in-flight-key"));
            release.countDown();
            assertEquals("late", late.get(5, TimeUnit.SECONDS));
        }
        assertEquals("fresh", invalidator.get("in-flight-key", String.class,
                Duration.ofMinutes(1), () -> "fresh"));
    }

    @Test
    void twoRootsNodesUseTheSameJdbcApplicationCache() throws Exception {
        var dataSource = dataSource();
        JdbcRootsCache.createSchema(dataSource);
        JdbcSessionRepository.createSchema(dataSource);
        var sessions = new JdbcSessionRepository(dataSource);
        Page.resetCacheLoads();

        try (var nodeA = Roots.start(RootsConfig.forApplication(Application.class)
                .port(0).development(false).sessionRepository(sessions)
                .cache(new JdbcRootsCache(dataSource)).build());
             var nodeB = Roots.start(RootsConfig.forApplication(Application.class)
                     .port(0).development(false).sessionRepository(sessions)
                     .cache(new JdbcRootsCache(dataSource)).build())) {
            var first = CLIENT.send(HttpRequest.newBuilder(nodeA.uri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var second = CLIENT.send(HttpRequest.newBuilder(nodeB.uri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, first.statusCode(), first.body());
            assertEquals(200, second.statusCode(), second.body());
            assertTrue(first.body().contains("shared load 1"), first.body());
            assertTrue(second.body().contains("shared load 1"), second.body());
            assertEquals(1, Page.cacheLoads());
        }
    }

    @Test
    void translatesDatabaseOutagesForEveryOperation() {
        DataSource unavailable = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new SQLException("offline", "08006");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var cache = new JdbcRootsCache(unavailable);

        assertDatabaseFailure(() -> JdbcRootsCache.createSchema(unavailable));
        assertDatabaseFailure(() -> cache.get("key", String.class, Duration.ofSeconds(1), () -> "value"));
        assertDatabaseFailure(() -> cache.invalidate("key"));
        assertDatabaseFailure(() -> cache.invalidateTag("tag"));
        assertDatabaseFailure(cache::clear);
        assertDatabaseFailure(cache::snapshot);
    }

    @Test
    void failsClosedWhenTheCacheMetadataMutexIsMissingOrCorrupt() throws Exception {
        var missingDataSource = dataSource();
        JdbcRootsCache.createSchema(missingDataSource);
        try (var connection = missingDataSource.getConnection();
             var statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("DELETE FROM roots_cache_meta WHERE lock_id = 1"));
        }
        var missing = assertThrows(JdbcCacheException.class,
                () -> new JdbcRootsCache(missingDataSource).snapshot());
        assertTrue(missing.getMessage().contains("metadata row is missing"), missing.toString());

        var corruptDataSource = dataSource();
        JdbcRootsCache.createSchema(corruptDataSource);
        try (var connection = corruptDataSource.getConnection();
             var statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "UPDATE roots_cache_meta SET cache_generation = -1 WHERE lock_id = 1"));
        }
        var corrupt = assertThrows(JdbcCacheException.class,
                () -> new JdbcRootsCache(corruptDataSource).snapshot());
        assertTrue(corrupt.getMessage().contains("metadata row is corrupt"), corrupt.toString());
    }

    @Test
    void publicApiIsExactAndDeliberatelySmall() {
        var constructors = Set.of(JdbcRootsCache.class.getConstructors()).stream()
                .map(constructor -> descriptor("new", constructor.getParameterTypes()))
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "new(javax.sql.DataSource)",
                "new(javax.sql.DataSource,dev.roots.jdbc.JdbcCacheValueCodec)",
                "new(javax.sql.DataSource,dev.roots.jdbc.JdbcCacheValueCodec,java.lang.String)",
                "new(javax.sql.DataSource,dev.roots.jdbc.JdbcCacheValueCodec,java.lang.String,int,java.time.Duration,java.time.Duration)"
        ), constructors);

        var methods = Set.of(JdbcRootsCache.class.getDeclaredMethods()).stream()
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> descriptor(method.getName(), method.getParameterTypes()))
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "schemaStatements()", "schemaStatements(java.lang.String)",
                "createSchema(javax.sql.DataSource)", "createSchema(javax.sql.DataSource,java.lang.String)",
                "get(java.lang.String,java.lang.Class,dev.roots.CachePolicy,java.util.function.Supplier)",
                "invalidate(java.lang.String)", "invalidateTag(java.lang.String)", "clear()", "snapshot()"
        ), methods);
        assertEquals(Set.of("DEFAULT_TABLE", "MAX_ENCODED_VALUE_LENGTH", "DEFAULT_LOAD_LEASE",
                        "DEFAULT_POLL_INTERVAL", "DEFAULT_MAX_ENTRIES"),
                Set.of(JdbcRootsCache.class.getFields()).stream()
                        .map(field -> field.getName()).collect(Collectors.toSet()));

        assertEquals(Set.of("encode(java.lang.Object)", "decode(java.lang.String,java.lang.Class)", "standard()"),
                Set.of(JdbcCacheValueCodec.class.getDeclaredMethods()).stream()
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(method -> descriptor(method.getName(), method.getParameterTypes()))
                        .collect(Collectors.toSet()));
        assertEquals(2, JdbcCacheException.class.getConstructors().length);
    }

    private static JdbcRootsCache cache(DataSource dataSource, AtomicLong clock, int maxEntries) {
        return cache(dataSource, clock, maxEntries, Duration.ofMinutes(5));
    }

    private static JdbcRootsCache cache(
            DataSource dataSource,
            AtomicLong clock,
            int maxEntries,
            Duration lease
    ) {
        return new JdbcRootsCache(dataSource, JdbcCacheValueCodec.standard(), JdbcRootsCache.DEFAULT_TABLE,
                maxEntries, lease, Duration.ofMillis(1), clock::get, ignored -> Thread.yield());
    }

    private static JdbcDataSource dataSource() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:roots_cache_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        return dataSource;
    }

    private static <T> T roundTrip(JdbcCacheValueCodec codec, T value, Class<T> type) {
        return codec.decode(codec.encode(value), type);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void awaitCondition(Supplier<Boolean> condition) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.get()) {
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError("Timed out waiting for test condition");
            }
            Thread.yield();
        }
    }

    private static void assertDatabaseFailure(Runnable operation) {
        var exception = assertThrows(JdbcCacheException.class, operation::run);
        assertInstanceOf(SQLException.class, exception.getCause(), exception.toString());
    }

    private static String descriptor(String name, Class<?>[] parameters) {
        return name + Arrays.stream(parameters)
                .map(Class::getName)
                .collect(Collectors.joining(",", "(", ")"));
    }
}
