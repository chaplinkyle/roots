package dev.roots;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TraceContextTest {
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT = "00f067aa0ba902b7";

    @Test
    void continuesValidW3cContextsWithFreshServerSpans() {
        var continued = TraceContext.fromTraceparent("00-" + TRACE + "-" + PARENT + "-03");

        assertEquals(TRACE, continued.traceId());
        assertEquals(Optional.of(PARENT), continued.parentSpanId());
        assertNotEquals(PARENT, continued.spanId());
        assertTrue(continued.sampled());
        assertTrue(continued.randomTraceId());
        assertEquals("00-" + TRACE + "-" + continued.spanId() + "-03", continued.traceparent());

        var child = continued.child();
        assertEquals(TRACE, child.traceId());
        assertEquals(Optional.of(continued.spanId()), child.parentSpanId());
        assertNotEquals(continued.spanId(), child.spanId());
    }

    @Test
    void restartsMalformedZeroUppercaseAndOversizedContexts() {
        for (var invalid : new String[] {
                null,
                "bad",
                "00-" + "0".repeat(32) + "-" + PARENT + "-01",
                "00-" + TRACE + "-" + "0".repeat(16) + "-01",
                "00-" + TRACE.toUpperCase() + "-" + PARENT + "-01",
                "ff-" + TRACE + "-" + PARENT + "-01",
                "00-" + TRACE + "-" + PARENT + "-01-extra",
                "01-" + TRACE + "-" + PARENT + "-01-" + "x".repeat(500)
        }) {
            var restarted = TraceContext.fromTraceparent(invalid);
            assertTrue(restarted.parentSpanId().isEmpty());
            assertTrue(restarted.randomTraceId());
            assertFalse(restarted.sampled());
            assertNotEquals(TRACE, restarted.traceId());
        }
    }

    @Test
    void acceptsFutureAdditiveVersionsAndMasksReservedFlags() {
        var continued = TraceContext.fromTraceparent("01-" + TRACE + "-" + PARENT + "-ff-vendor");
        assertEquals(TRACE, continued.traceId());
        assertEquals(3, continued.traceFlags());
        assertTrue(continued.traceparent().startsWith("00-" + TRACE + "-"));
    }

    @Test
    void validatesPublicValueTypesAndProducesSafeStructuredJson() {
        assertThrows(IllegalArgumentException.class,
                () -> new TraceContext("0".repeat(32), PARENT, Optional.empty(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceContext(TRACE, "ABCDEF0123456789", Optional.empty(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceContext(TRACE, PARENT, Optional.empty(), 256));

        var observation = new RequestObservation(
                new TraceContext(TRACE, PARENT, Optional.empty(), 1),
                Instant.parse("2026-08-16T12:00:00Z"),
                Duration.ofMillis(12),
                "get",
                "/customers/\"quoted\"",
                "/customers",
                503,
                41
        );
        assertEquals(RequestOutcome.SERVER_ERROR, observation.outcome());
        var json = observation.json();
        assertTrue(json.contains("\"event\":\"roots.request\""), json);
        assertTrue(json.contains("customers/\\\"quoted\\\""), json);
        assertTrue(json.contains("\"outcome\":\"server_error\""), json);
        assertFalse(json.contains("cookie"), json);
        assertEquals(RequestOutcome.ABORTED, RequestOutcome.fromStatus(0));
        assertEquals(RequestOutcome.SUCCESS, RequestOutcome.fromStatus(204));
        assertEquals(RequestOutcome.REDIRECTION, RequestOutcome.fromStatus(302));
        assertEquals(RequestOutcome.CLIENT_ERROR, RequestOutcome.fromStatus(404));
        assertThrows(IllegalArgumentException.class, () -> new RequestObservation(
                TraceContext.create(), Instant.now(), Duration.ofSeconds(-1), "GET", "/", "/", 200, 0));
        assertThrows(IllegalArgumentException.class, () -> new RequestObservation(
                TraceContext.create(), Instant.now(), Duration.ZERO, "GET", "relative", "/", 200, 0));
        assertThrows(IllegalArgumentException.class, () -> new RequestObservation(
                TraceContext.create(), Instant.now(), Duration.ZERO, "GET", "/", "/", 99, 0));
        assertThrows(IllegalArgumentException.class, () -> new RequestObservation(
                TraceContext.create(), Instant.now(), Duration.ZERO, "GET", "/", "/", 200, -2));

        var logger = new RecordingLogger();
        RequestObserver.structured(logger).onComplete(observation);
        assertEquals(System.Logger.Level.INFO, logger.level.get());
        assertEquals(json, logger.message.get());
        assertThrows(NullPointerException.class, () -> RequestObserver.structured(null));
    }

    private static final class RecordingLogger implements System.Logger {
        private final AtomicReference<Level> level = new AtomicReference<>();
        private final AtomicReference<String> message = new AtomicReference<>();

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            this.level.set(level);
            this.message.set(message);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... parameters) {
            this.level.set(level);
            this.message.set(format.formatted(parameters));
        }
    }
}
