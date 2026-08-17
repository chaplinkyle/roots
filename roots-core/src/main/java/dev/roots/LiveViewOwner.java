package dev.roots;

import java.time.Instant;
import java.util.Objects;

/**
 * A lease identifying the application node that owns one in-memory live view.
 *
 * @param nodeId stable deployment-local node identifier
 * @param sessionId session identifier bound to the view
 * @param expiresAt exclusive lease expiry
 */
public record LiveViewOwner(String nodeId, String sessionId, Instant expiresAt) {
    /** Validates an ownership lease. */
    public LiveViewOwner {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (nodeId.isBlank() || nodeId.length() > 256 || nodeId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Node id must be nonblank, control-free, and at most 256 characters");
        }
        if (sessionId.isBlank() || sessionId.length() > 512
                || sessionId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Session id must be nonblank, control-free, and at most 512 characters");
        }
    }
}
