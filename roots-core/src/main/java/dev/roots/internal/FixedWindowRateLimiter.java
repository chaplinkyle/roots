package dev.roots.internal;

import dev.roots.RateLimitDecision;
import dev.roots.RateLimitRequest;
import dev.roots.RateLimiter;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Internal bounded fixed-window limiter used by the public factory. */
public final class FixedWindowRateLimiter implements RateLimiter {
    private final int requests;
    private final long windowNanos;
    private final Duration window;
    private final int maxClients;
    private final LongSupplier nanoTime;
    private final Map<String, Window> clients = new HashMap<>();

    /** Creates a limiter using the monotonic system clock.
     * @param requests requests per window
     * @param window fixed window
     * @param maxClients retained client-key cap
     */
    public FixedWindowRateLimiter(int requests, Duration window, int maxClients) {
        this(requests, window, maxClients, System::nanoTime);
    }

    FixedWindowRateLimiter(int requests, Duration window, int maxClients, LongSupplier nanoTime) {
        if (requests < 1 || maxClients < 1) {
            throw new IllegalArgumentException("Rate limit and client capacity must be positive");
        }
        this.window = Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate-limit window must be positive");
        }
        try {
            windowNanos = window.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Rate-limit window is too large", exception);
        }
        if (windowNanos < 1) {
            throw new IllegalArgumentException("Rate-limit window must be at least one nanosecond");
        }
        this.requests = requests;
        this.maxClients = maxClients;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public synchronized RateLimitDecision acquire(RateLimitRequest request) {
        Objects.requireNonNull(request, "request");
        var now = nanoTime.getAsLong();
        var key = request.connection().clientAddressText();
        var current = clients.get(key);
        if (current == null) {
            if (clients.size() >= maxClients) {
                removeExpired(now);
            }
            if (clients.size() >= maxClients) {
                return RateLimitDecision.reject(requests, untilEarliestReset(now));
            }
            clients.put(key, new Window(now, 1));
            return RateLimitDecision.allow(requests, requests - 1L, window);
        }
        var elapsed = now - current.startedAt();
        if (elapsed < 0 || elapsed >= windowNanos) {
            clients.put(key, new Window(now, 1));
            return RateLimitDecision.allow(requests, requests - 1L, window);
        }
        var reset = Duration.ofNanos(windowNanos - elapsed);
        if (current.count() >= requests) {
            return RateLimitDecision.reject(requests, reset);
        }
        var count = current.count() + 1;
        clients.put(key, new Window(current.startedAt(), count));
        return RateLimitDecision.allow(requests, requests - (long) count, reset);
    }

    private void removeExpired(long now) {
        clients.entrySet().removeIf(entry -> {
            var elapsed = now - entry.getValue().startedAt();
            return elapsed < 0 || elapsed >= windowNanos;
        });
    }

    private Duration untilEarliestReset(long now) {
        var remaining = windowNanos;
        for (var value : clients.values()) {
            var elapsed = now - value.startedAt();
            if (elapsed >= 0) {
                remaining = Math.min(remaining, Math.max(1, windowNanos - elapsed));
            }
        }
        return Duration.ofNanos(remaining);
    }

    private record Window(long startedAt, int count) {
    }
}
