package com.chaplin.roots;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class IdempotencyStoreTest {
    private static final String HASH = "a".repeat(64);
    private static final Duration RETENTION = Duration.ofMinutes(5);

    @Test
    void replaysCompletedRequestsAndIsolatesAuthenticatedScopes() throws Exception {
        var store = IdempotencyStore.inMemory(10, 1024);
        var mutations = new AtomicInteger();
        IdempotencyStore.Work work = () -> Response.text(201, "result-" + mutations.incrementAndGet());
        assertEquals("result-1", store.execute("tenant-A:create", "key", HASH, RETENTION, work).bodyText());
        var replay = store.execute("tenant-A:create", "key", HASH, RETENTION, work);
        assertEquals("result-1", replay.bodyText());
        assertEquals("true", replay.headers().get("Idempotency-Replayed").getFirst());
        assertEquals(422, store.execute("tenant-A:create", "key", "b".repeat(64), RETENTION, work).status());
        assertEquals("result-2", store.execute("tenant-B:create", "key", HASH, RETENTION, work).bodyText());
        assertEquals(2, mutations.get());
    }

    @Test
    void onlyOneConcurrentCallerRunsTheMutation() throws Exception {
        var store = IdempotencyStore.inMemory(10, 1024);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executions = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = executor.submit(() -> store.execute("scope", "key", HASH, RETENTION, () -> {
                executions.incrementAndGet();
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return Response.text(200, "saved");
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var contenders = java.util.stream.IntStream.range(0, 32).mapToObj(index -> executor.submit(() ->
                        store.execute("scope", "key", HASH, RETENTION, () -> {
                            executions.incrementAndGet(); return Response.text(200, "duplicate");
                        }))).toList();
                for (var contender : contenders) assertEquals(409, contender.get(2, TimeUnit.SECONDS).status());
            } finally { release.countDown(); }
            assertEquals(200, owner.get(2, TimeUnit.SECONDS).status());
            assertEquals(1, executions.get());
        }
    }

    @Test
    void doesNotEvictUnexpiredOrInFlightReceiptsToMakeRoom() throws Exception {
        var clock = new MutableClock();
        var store = new InMemoryIdempotencyStore(1, 1024, clock);
        store.execute("scope", "first", HASH, Duration.ofSeconds(1), () -> Response.text(200, "first"));
        assertEquals(503, store.execute("scope", "second", HASH, RETENTION, () -> fail("must not execute")).status());
        clock.now = clock.now.plusSeconds(1);
        assertEquals(200, store.execute("scope", "second", HASH, RETENTION, () -> Response.text(200, "second")).status());
    }

    @Test
    void retainsUncertainOutcomesInsteadOfRepeatingPotentialSideEffects() throws Exception {
        var store = IdempotencyStore.inMemory(10, 1024);
        assertThrows(IOException.class, () -> store.execute("scope", "failed", HASH, RETENTION, () -> {
            throw new IOException("connection lost after commit");
        }));
        var retry = store.execute("scope", "failed", HASH, RETENTION, () -> fail("must not retry mutation"));
        assertEquals(409, retry.status());
        assertTrue(retry.bodyText().contains("outcome-unknown"));
        assertFalse(retry.bodyText().contains("connection lost"));
        assertThrows(IllegalArgumentException.class, () -> store.execute("scope", "large", HASH, RETENTION,
                () -> Response.text(200, "x".repeat(1025))));
        assertThrows(IllegalArgumentException.class, () -> store.execute("scope", "cookie", HASH, RETENTION,
                () -> Response.text(200, "ok").withHeader("Set-Cookie", "secret=value")));
        assertThrows(IllegalArgumentException.class, () -> store.execute("scope", "stream", HASH, RETENTION,
                () -> Response.stream(200, "text/plain", output -> fail("writer must not execute"))));
    }

    @Test
    void fingerprintsCanonicalMetadataAndExactBodyBytes() throws Exception {
        var first = request(Map.of("q", List.of("a", "b")), "body");
        var same = request(Map.of("q", List.of("a", "b")), "body");
        assertEquals(IdempotencyStore.fingerprint(first), IdempotencyStore.fingerprint(same));
        assertNotEquals(IdempotencyStore.fingerprint(first), IdempotencyStore.fingerprint(request(Map.of("q", List.of("b", "a")), "body")));
        assertNotEquals(IdempotencyStore.fingerprint(first), IdempotencyStore.fingerprint(request(Map.of("q", List.of("a", "b")), "body ")));
        assertNotEquals(IdempotencyStore.fingerprint(request(Map.of("a", List.of("bc")), "body")),
                IdempotencyStore.fingerprint(request(Map.of("ab", List.of("c")), "body")));
        assertEquals("body", first.bodyText());
    }

    @Test
    void validatesReceiptLimitsAndKeys() {
        assertThrows(IllegalArgumentException.class, () -> IdempotencyStore.inMemory(0, 1024));
        var store = IdempotencyStore.inMemory(1, 1);
        for (var key : List.of("", "a b", "x".repeat(201))) {
            assertThrows(IllegalArgumentException.class, () -> store.execute("scope", key, HASH, RETENTION, Response::noContent));
        }
        assertThrows(IllegalArgumentException.class, () -> store.execute("", "key", HASH, RETENTION, Response::noContent));
        assertThrows(IllegalArgumentException.class, () -> store.execute("scope", "key", "bad", RETENTION, Response::noContent));
        assertThrows(IllegalArgumentException.class, () -> store.execute("scope", "key", HASH, Duration.ZERO, Response::noContent));
    }

    private Request request(Map<String, List<String>> query, String body) {
        return new Request("POST", "/api/items", Map.of(), query, Map.of("Content-Type", List.of("text/plain")),
                Map.of(), body.getBytes(StandardCharsets.UTF_8), new Session("request"));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-06T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
