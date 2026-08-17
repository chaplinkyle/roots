package dev.roots.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DevelopmentEventsTest {
    @Test
    void publishesOrderedApplicationLocalEventsAndWakesWaiters() throws Exception {
        var firstApplication = "test." + UUID.randomUUID();
        var secondApplication = "test." + UUID.randomUUID();
        assertEquals(DevelopmentEvents.Kind.INITIAL, DevelopmentEvents.current(firstApplication).kind());

        var waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return DevelopmentEvents.awaitAfter(firstApplication, 0, Duration.ofSeconds(2)).orElseThrow();
            } catch (InterruptedException exception) {
                throw new IllegalStateException(exception);
            }
        });
        var failure = DevelopmentEvents.failure(firstApplication, "broken");

        assertEquals(failure, waiting.get(2, TimeUnit.SECONDS));
        assertEquals(1, failure.version());
        assertEquals(DevelopmentEvents.Kind.FAILURE, failure.kind());
        assertEquals(DevelopmentEvents.Kind.INITIAL, DevelopmentEvents.current(secondApplication).kind());
        assertEquals(2, DevelopmentEvents.reload(firstApplication).version());
    }

    @Test
    void boundsDiagnosticsAndTimesOutWithoutInventingEvents() throws Exception {
        var application = "test." + UUID.randomUUID();
        var failure = DevelopmentEvents.failure(application, "x".repeat(40_000));

        assertEquals(32 * 1024, failure.message().length());
        assertTrue(DevelopmentEvents.awaitAfter(application, failure.version(), Duration.ofMillis(1)).isEmpty());
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(NullPointerException.class, () -> DevelopmentEvents.current(null));
        assertThrows(IllegalArgumentException.class, () -> DevelopmentEvents.current(" "));
        assertThrows(IllegalArgumentException.class,
                () -> DevelopmentEvents.awaitAfter("test.Valid", -1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> DevelopmentEvents.awaitAfter("test.Valid", 0, Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> DevelopmentEvents.failure("test.Valid", null));
        assertThrows(IllegalArgumentException.class,
                () -> new DevelopmentEvents.Event(-1, DevelopmentEvents.Kind.RELOAD, ""));
    }
}
