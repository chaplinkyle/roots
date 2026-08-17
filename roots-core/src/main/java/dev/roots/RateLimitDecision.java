package dev.roots;

import java.time.Duration;
import java.util.Objects;

/**
 * Result returned by a {@link RateLimiter}.
 *
 * @param allowed whether request processing may continue
 * @param limit advertised request limit, or zero for an unlimited decision
 * @param remaining non-negative requests remaining in the current window
 * @param resetAfter duration until the current limit resets
 */
public record RateLimitDecision(boolean allowed, long limit, long remaining, Duration resetAfter) {
    /** Validates a decision. */
    public RateLimitDecision {
        resetAfter = Objects.requireNonNull(resetAfter, "resetAfter");
        if (limit < 0 || remaining < 0 || remaining > limit || resetAfter.isNegative()) {
            throw new IllegalArgumentException("Rate-limit decision values are invalid");
        }
        if (!allowed && limit == 0) {
            throw new IllegalArgumentException("An unlimited rate-limit decision cannot reject a request");
        }
    }

    /** Returns an unlimited allow decision.
     * @return unlimited decision
     */
    public static RateLimitDecision unlimited() {
        return new RateLimitDecision(true, 0, 0, Duration.ZERO);
    }

    /** Creates a bounded allow decision.
     * @param limit request limit
     * @param remaining requests remaining after this request
     * @param resetAfter duration until reset
     * @return allow decision
     */
    public static RateLimitDecision allow(long limit, long remaining, Duration resetAfter) {
        return new RateLimitDecision(true, limit, remaining, resetAfter);
    }

    /** Creates a bounded rejection decision.
     * @param limit request limit
     * @param resetAfter duration until retry
     * @return rejection decision
     */
    public static RateLimitDecision reject(long limit, Duration resetAfter) {
        return new RateLimitDecision(false, limit, 0, resetAfter);
    }
}
