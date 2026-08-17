package dev.roots;

import java.time.Instant;
import java.util.Optional;

/**
 * Pluggable lease registry for routing a live view to the JVM that owns its Java
 * component graph.
 *
 * <p>Implementations may use a distributed store. Claim and renew operations
 * must be atomic for one view identifier. Availability failures should be
 * reported with {@link LiveViewOwnershipException}.</p>
 */
public interface LiveViewOwnership {
    /** Returns the identifier advertised by this application node.
     * @return nonblank node identifier */
    String localNodeId();

    /**
     * Claims an absent or expired view lease for the local node.
     *
     * @param viewId live-view identifier
     * @param sessionId session identifier bound to the view
     * @param now current wall-clock instant
     * @param expiresAt exclusive lease expiry after {@code now}
     * @return whether the claim belongs to this node after the operation
     */
    boolean claim(String viewId, String sessionId, Instant now, Instant expiresAt);

    /**
     * Renews a non-expired lease only when it is still owned by the local node.
     *
     * @param viewId live-view identifier
     * @param sessionId session identifier bound to the view
     * @param now current wall-clock instant
     * @param expiresAt new exclusive lease expiry after {@code now}
     * @return whether the lease was renewed
     */
    boolean renew(String viewId, String sessionId, Instant now, Instant expiresAt);

    /** Finds a non-expired owner without changing its lease.
     * @param viewId live-view identifier
     * @param now current wall-clock instant
     * @return current owner, if any */
    Optional<LiveViewOwner> find(String viewId, Instant now);

    /** Releases a lease only when it is still owned by the local node.
     * @param viewId live-view identifier
     * @param sessionId session identifier bound to the view */
    void release(String viewId, String sessionId);

    /** Removes leases expired at or before {@code now}.
     * @param now current wall-clock instant */
    void deleteExpired(Instant now);

    /** Releases resources owned by this registry. Shared distributed stores should normally do nothing. */
    default void close() {
    }

    /** Creates a thread-safe node-local registry with a generated node identifier.
     * @return ownership registry */
    static LiveViewOwnership inMemory() {
        return new InMemoryLiveViewOwnership();
    }

    /** Creates a thread-safe node-local registry with an explicit node identifier.
     * @param nodeId local node identifier
     * @return ownership registry */
    static LiveViewOwnership inMemory(String nodeId) {
        return new InMemoryLiveViewOwnership(nodeId);
    }
}
