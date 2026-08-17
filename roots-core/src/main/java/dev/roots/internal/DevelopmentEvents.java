package dev.roots.internal;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local bridge used by Roots development tooling to notify connected browsers.
 * This type is public only so a development runner in a separate module can use it; it is
 * not an application-facing API.
 */
public final class DevelopmentEvents {
    private static final int MAXIMUM_MESSAGE_LENGTH = 32 * 1024;
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private DevelopmentEvents() {
    }

    /** Describes a development notification. */
    public enum Kind {
        /** No build has completed in this process yet. */
        INITIAL,
        /** A new application build is ready. */
        RELOAD,
        /** The latest build attempt failed. */
        FAILURE
    }

    /** An immutable development notification.
     * @param version monotonically increasing application-local version
     * @param kind notification kind
     * @param message bounded compiler or startup diagnostic */
    public record Event(long version, Kind kind, String message) {
        /** Creates a validated event. */
        public Event {
            if (version < 0) {
                throw new IllegalArgumentException("Development event version must not be negative");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(message, "message");
        }
    }

    /** Returns the current notification for an application.
     * @param applicationName binary application class name
     * @return current notification */
    public static Event current(String applicationName) {
        return state(applicationName).current();
    }

    /** Waits for a notification newer than a browser's known version.
     * @param applicationName binary application class name
     * @param version last observed version
     * @param timeout maximum wait
     * @return a newer notification, or empty after the timeout
     * @throws InterruptedException if interrupted while waiting */
    public static Optional<Event> awaitAfter(String applicationName, long version, Duration timeout)
            throws InterruptedException {
        if (version < 0) {
            throw new IllegalArgumentException("Development event version must not be negative");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Development event timeout must not be negative");
        }
        return state(applicationName).awaitAfter(version, timeout);
    }

    /** Announces that a successful application build should replace the browser document.
     * @param applicationName binary application class name
     * @return published notification */
    public static Event reload(String applicationName) {
        return state(applicationName).publish(Kind.RELOAD, "");
    }

    /** Announces a failed build while leaving the last good application running.
     * @param applicationName binary application class name
     * @param diagnostic compiler or startup diagnostic
     * @return published notification */
    public static Event failure(String applicationName, String diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        var bounded = diagnostic.length() <= MAXIMUM_MESSAGE_LENGTH
                ? diagnostic
                : diagnostic.substring(diagnostic.length() - MAXIMUM_MESSAGE_LENGTH);
        return state(applicationName).publish(Kind.FAILURE, bounded);
    }

    private static State state(String applicationName) {
        Objects.requireNonNull(applicationName, "applicationName");
        if (applicationName.isBlank()) {
            throw new IllegalArgumentException("Application name must not be blank");
        }
        return STATES.computeIfAbsent(applicationName, ignored -> new State());
    }

    private static final class State {
        private Event event = new Event(0, Kind.INITIAL, "");

        private synchronized Event current() {
            return event;
        }

        private synchronized Event publish(Kind kind, String message) {
            if (event.version() == Long.MAX_VALUE) {
                throw new IllegalStateException("Development event version is exhausted");
            }
            event = new Event(event.version() + 1, kind, message);
            notifyAll();
            return event;
        }

        private synchronized Optional<Event> awaitAfter(long version, Duration timeout) throws InterruptedException {
            if (event.version() > version) {
                return Optional.of(event);
            }
            var remainingNanos = timeout.toNanos();
            var deadline = System.nanoTime() + remainingNanos;
            while (event.version() <= version && remainingNanos > 0) {
                var millis = remainingNanos / 1_000_000;
                var nanos = (int) (remainingNanos % 1_000_000);
                wait(millis, nanos);
                remainingNanos = deadline - System.nanoTime();
            }
            return event.version() > version ? Optional.of(event) : Optional.empty();
        }
    }
}
