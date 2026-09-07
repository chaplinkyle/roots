package com.chaplin.roots;

import java.time.Instant;
import java.util.Optional;

/**
 * Pluggable owner of session identity, idle expiry, capacity, and value storage.
 *
 * <p>Implementations must make {@link #findAndTouch} and {@link #create} atomic
 * for a session identifier. A distributed implementation may return a
 * {@link Session} subclass whose value methods delegate to an external store.
 * Transient availability failures should be reported with
 * {@link SessionRepositoryException} so Roots can return {@code 503}.</p>
 */
public interface SessionRepository {
    /**
     * Finds a non-expired session and atomically extends its idle expiry.
     *
     * @param id requested cookie identifier
     * @param accessedAt current wall-clock instant
     * @param expiresAt new exclusive expiry instant
     * @return the live session, or empty when absent or expired
     */
    Optional<Session> findAndTouch(String id, Instant accessedAt, Instant expiresAt);

    /**
     * Atomically creates a session when capacity permits.
     *
     * <p>The returned session identifier must be generated from a
     * cryptographically secure source and contain no cookie separators.</p>
     *
     * @param expiresAt exclusive idle-expiry instant
     * @param maximumSessions maximum sessions this repository may retain
     * @return a new session, or empty when capacity is exhausted
     */
    Optional<Session> create(Instant expiresAt, int maximumSessions);

    /** Removes sessions whose expiry is at or before the supplied instant.
     * @param now current wall-clock instant */
    void deleteExpired(Instant now);

    /** Returns the repository's current session count.
     * @return active and not-yet-cleaned session count */
    int size();

    /**
     * Releases repository resources when the owning Roots runtime stops.
     *
     * <p>The default is a no-op so a shared external repository is not cleared
     * when one application node shuts down.</p>
     */
    default void close() {
    }

    /** Creates the bounded, node-local default repository.
     * @return in-memory repository */
    static SessionRepository inMemory() {
        return new InMemorySessionRepository();
    }
}
