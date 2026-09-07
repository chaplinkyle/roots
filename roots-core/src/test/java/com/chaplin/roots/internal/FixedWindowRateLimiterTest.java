package com.chaplin.roots.internal;

import com.chaplin.roots.ClientConnection;
import com.chaplin.roots.RateLimitRequest;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedWindowRateLimiterTest {
    @Test
    void enforcesAndResetsExactClientWindows() {
        var clock = new AtomicLong(1_000);
        var limiter = new FixedWindowRateLimiter(2, Duration.ofSeconds(10), 2, clock::get);
        var request = request("192.0.2.1");

        var first = limiter.acquire(request);
        assertTrue(first.allowed());
        assertEquals(1, first.remaining());
        assertEquals(Duration.ofSeconds(10), first.resetAfter());

        clock.addAndGet(Duration.ofSeconds(3).toNanos());
        assertEquals(0, limiter.acquire(request).remaining());
        var rejected = limiter.acquire(request);
        assertFalse(rejected.allowed());
        assertEquals(Duration.ofSeconds(7), rejected.resetAfter());

        clock.addAndGet(Duration.ofSeconds(7).toNanos());
        assertTrue(limiter.acquire(request).allowed());
    }

    @Test
    void boundsClientKeysAndReclaimsExpiredEntries() {
        var clock = new AtomicLong();
        var limiter = new FixedWindowRateLimiter(5, Duration.ofSeconds(1), 1, clock::get);

        assertTrue(limiter.acquire(request("192.0.2.1")).allowed());
        assertFalse(limiter.acquire(request("192.0.2.2")).allowed());
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        assertTrue(limiter.acquire(request("192.0.2.2")).allowed());
    }

    @Test
    void admitsExactlyTheConfiguredConcurrentCount() throws Exception {
        var limiter = new FixedWindowRateLimiter(10, Duration.ofMinutes(1), 100, () -> 0L);
        var start = new CountDownLatch(1);
        var allowed = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (var index = 0; index < 64; index++) {
                tasks.add(executor.submit(() -> {
                    start.await();
                    if (limiter.acquire(request("198.51.100.4")).allowed()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) {
                task.get();
            }
        }
        assertEquals(10, allowed.get());
    }

    @Test
    void validatesConfigurationAndRequestHeads() {
        assertThrows(IllegalArgumentException.class,
                () -> new FixedWindowRateLimiter(0, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedWindowRateLimiter(1, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedWindowRateLimiter(1, Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimitRequest("get", "relative", ClientConnection.unknown("http", "localhost")));
    }

    private static RateLimitRequest request(String address) {
        return new RateLimitRequest("GET", "/api", ClientConnection.direct(
                InetAddress.ofLiteral(address), "https", "example.test"
        ));
    }
}
