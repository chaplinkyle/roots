package dev.roots;

import dev.roots.internal.FixedWindowRateLimiter;

import java.time.Duration;

/**
 * Admission policy evaluated before Roots allocates a session or reads a body.
 * Implementations must be thread-safe and should keep key cardinality bounded.
 */
@FunctionalInterface
public interface RateLimiter {
    /** Acquires capacity for one request.
     * @param request immutable request head
     * @return allow or rejection decision
     */
    RateLimitDecision acquire(RateLimitRequest request);

    /** Returns a limiter that allows every request without emitting limit headers.
     * @return unlimited limiter
     */
    static RateLimiter unlimited() {
        return ignored -> RateLimitDecision.unlimited();
    }

    /**
     * Creates a bounded node-local fixed-window limiter keyed by resolved client address.
     * Unknown clients share one key. When the client-key cap is full and no expired
     * entry can be reclaimed, new keys are rejected until the earliest window resets.
     *
     * @param requests maximum requests per client and window
     * @param window positive fixed window
     * @param maxClients maximum retained client keys
     * @return thread-safe limiter
     */
    static RateLimiter fixedWindow(int requests, Duration window, int maxClients) {
        return new FixedWindowRateLimiter(requests, window, maxClients);
    }
}
