package dev.roots;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RootsCacheTest {
    @Test
    void cachesByKeyExpiresValuesAndEvictsLeastRecentlyUsedEntries() {
        var ticker = new AtomicLong(100);
        var cache = new InMemoryRootsCache(2, ticker::get);
        var loads = new AtomicInteger();
        var policy = CachePolicy.forDuration(Duration.ofNanos(10));

        assertEquals("a-1", cache.get("a", String.class, policy, () -> "a-" + loads.incrementAndGet()));
        assertEquals("a-1", cache.get("a", String.class, policy, () -> "unused"));
        assertEquals("b-2", cache.get("b", String.class, policy, () -> "b-" + loads.incrementAndGet()));
        assertEquals("a-1", cache.get("a", String.class, policy, () -> "unused"));
        assertEquals("c-3", cache.get("c", String.class, policy, () -> "c-" + loads.incrementAndGet()));

        var populated = cache.snapshot();
        assertEquals(2, populated.entries());
        assertEquals(2, populated.hits());
        assertEquals(3, populated.misses());
        assertEquals(3, populated.loads());
        assertEquals(1, populated.evictions());

        ticker.addAndGet(10);
        var expired = cache.snapshot();
        assertEquals(0, expired.entries());
        assertEquals(2, expired.expirations());
    }

    @Test
    void invalidatesExactKeysTagsAndAllValues() {
        var cache = RootsCache.inMemory(4);
        var minute = Duration.ofMinutes(1);
        cache.get("customer:1", String.class, CachePolicy.tagged(minute, "customers", "customer:1"), () -> "one");
        cache.get("customer:2", String.class, CachePolicy.tagged(minute, "customers", "customer:2"), () -> "two");
        cache.get("invoice:1", String.class, CachePolicy.tagged(minute, "invoices"), () -> "invoice");

        assertEquals(2, cache.invalidateTag("customers"));
        assertFalse(cache.invalidate("missing"));
        assertTrue(cache.invalidate("invoice:1"));
        assertEquals(0, cache.snapshot().entries());
        assertEquals(3, cache.snapshot().invalidations());

        cache.get("again", String.class, minute, () -> "value");
        cache.clear();
        assertEquals(0, cache.snapshot().entries());
        assertEquals(4, cache.snapshot().invalidations());
    }

    @Test
    void coalescesConcurrentMissesIntoOneLoaderCall() throws Exception {
        var cache = RootsCache.inMemory(8);
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var loads = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, 32)
                    .mapToObj(index -> executor.submit(() -> cache.get(
                            "shared",
                            String.class,
                            Duration.ofMinutes(1),
                            () -> {
                                loads.incrementAndGet();
                                ready.countDown();
                                await(release);
                                return "loaded";
                            }
                    )))
                    .toList();

            assertTrue(ready.await(2, TimeUnit.SECONDS));
            awaitCondition(() -> cache.snapshot().misses() == 32);
            release.countDown();
            for (var future : futures) {
                assertEquals("loaded", future.get(2, TimeUnit.SECONDS));
            }
        }

        var snapshot = cache.snapshot();
        assertEquals(1, loads.get());
        assertEquals(1, snapshot.loads());
        assertEquals(31, snapshot.coalescedLoads());
        assertEquals(1, snapshot.entries());
    }

    @Test
    void invalidationDuringALoadPreventsStaleInsertion() throws Exception {
        var cache = RootsCache.inMemory(8);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var policy = CachePolicy.tagged(Duration.ofMinutes(1), "accounts");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var loading = executor.submit(() -> cache.get("accounts:list", String.class, policy, () -> {
                started.countDown();
                await(release);
                return "stale";
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(1, cache.invalidateTag("accounts"));
            assertEquals("fresh", cache.get("accounts:list", String.class, policy, () -> "fresh"));
            release.countDown();
            assertEquals("stale", loading.get(2, TimeUnit.SECONDS));
        }

        assertEquals(1, cache.snapshot().entries());
        assertEquals("fresh", cache.get("accounts:list", String.class, policy, () -> "unused"));
        assertEquals(2, cache.snapshot().loads());
    }

    @Test
    void validatesPoliciesKeysTypesFailuresAndDisabledMode() {
        var tags = new LinkedHashSet<String>();
        tags.add("customers");
        var policy = new CachePolicy(Duration.ofSeconds(1), tags);
        tags.add("later");
        assertEquals(java.util.Set.of("customers"), policy.tags());
        assertThrows(UnsupportedOperationException.class, () -> policy.tags().add("nope"));
        assertThrows(IllegalArgumentException.class, () -> CachePolicy.forDuration(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new CachePolicy(Duration.ofSeconds(1), java.util.Set.of("bad\nvalue")));
        assertThrows(IllegalArgumentException.class, () -> RootsCache.inMemory(0));

        var cache = RootsCache.inMemory(2);
        assertThrows(IllegalArgumentException.class,
                () -> cache.get(" ", String.class, policy, () -> "value"));
        assertThrows(NullPointerException.class,
                () -> cache.get("null", String.class, policy, () -> null));
        assertThrows(IllegalStateException.class,
                () -> cache.get("failure", String.class, policy, () -> {
                    throw new IllegalStateException("expected");
                }));
        assertEquals("retry", cache.get("failure", String.class, policy, () -> "retry"));
        cache.get("typed", String.class, policy, () -> "text");
        assertThrows(IllegalStateException.class,
                () -> cache.get("typed", Integer.class, policy, () -> 42));

        var disabled = RootsCache.disabled();
        var disabledLoads = new AtomicInteger();
        assertEquals(1, disabled.get("key", Integer.class, policy, disabledLoads::incrementAndGet));
        assertEquals(2, disabled.get("key", Integer.class, policy, disabledLoads::incrementAndGet));
        assertFalse(disabled.snapshot().enabled());
        assertFalse(disabled.invalidate("key"));
        assertEquals(0, disabled.invalidateTag("customers"));
        disabled.clear();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for cache test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for cache condition");
            }
            Thread.sleep(1);
        }
    }
}
