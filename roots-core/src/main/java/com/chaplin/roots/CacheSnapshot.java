package com.chaplin.roots;

/**
 * Immutable diagnostics for one Roots cache.
 *
 * @param enabled whether values can be retained
 * @param entries currently retained values
 * @param maxEntries configured capacity
 * @param hits direct value hits
 * @param misses calls that did not find a current value
 * @param loads loader invocations
 * @param coalescedLoads misses that joined an existing load
 * @param evictions capacity-driven removals
 * @param expirations time-driven removals
 * @param invalidations keys affected by explicit revalidation
 */
public record CacheSnapshot(
        boolean enabled,
        int entries,
        int maxEntries,
        long hits,
        long misses,
        long loads,
        long coalescedLoads,
        long evictions,
        long expirations,
        long invalidations
) {
}
