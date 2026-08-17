package dev.roots;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Application-scoped tagged data cache used by pages, API routes, and jobs.
 * Implementations must make values visible safely across request threads.
 */
public interface RootsCache {
    /** Default maximum retained entries for an in-memory cache. */
    int DEFAULT_MAX_ENTRIES = 10_000;

    /**
     * Returns a current value or loads and caches one. Concurrent misses for the
     * same key should share one load.
     *
     * @param key stable application cache key
     * @param type runtime value type
     * @param policy expiry and tag policy
     * @param loader value loader, invoked only on a cache miss
     * @param <T> value type
     * @return cached or loaded non-null value
     */
    <T> T get(String key, Class<T> type, CachePolicy policy, Supplier<? extends T> loader);

    /** Returns or loads an untagged value.
     * @param <T> value type
     * @param key stable cache key
     * @param type runtime value type
     * @param timeToLive expiry duration
     * @param loader value loader
     * @return cached or loaded value */
    default <T> T get(String key, Class<T> type, Duration timeToLive, Supplier<? extends T> loader) {
        return get(key, type, CachePolicy.forDuration(timeToLive), loader);
    }

    /** Invalidates one key, including an in-flight load for that key.
     * @param key cache key
     * @return whether a value or load was affected */
    boolean invalidate(String key);

    /**
     * Invalidates cached and in-flight values carrying {@code tag}.
     *
     * @param tag cache tag
     * @return number of affected keys
     */
    long invalidateTag(String tag);

    /** Invalidates all current and in-flight values. */
    void clear();

    /** Returns immutable cache diagnostics.
     * @return cache snapshot */
    CacheSnapshot snapshot();

    /** Creates a bounded application-local cache with the default capacity.
     * @return in-memory cache */
    static RootsCache inMemory() {
        return inMemory(DEFAULT_MAX_ENTRIES);
    }

    /**
     * Creates a bounded application-local cache.
     *
     * @param maxEntries maximum retained values
     * @return a bounded application-local cache
     */
    static RootsCache inMemory(int maxEntries) {
        return new InMemoryRootsCache(maxEntries);
    }

    /** Creates a cache implementation that always invokes loaders.
     * @return disabled cache */
    static RootsCache disabled() {
        return DisabledRootsCache.INSTANCE;
    }
}
