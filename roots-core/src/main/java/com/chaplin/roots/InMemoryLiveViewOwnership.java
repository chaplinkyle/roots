package com.chaplin.roots;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class InMemoryLiveViewOwnership implements LiveViewOwnership {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String nodeId;
    private final Map<String, LiveViewOwner> leases = new ConcurrentHashMap<>();

    InMemoryLiveViewOwnership() {
        this(generatedNodeId());
    }

    InMemoryLiveViewOwnership(String nodeId) {
        this.nodeId = new LiveViewOwner(nodeId, "validation", Instant.EPOCH).nodeId();
    }

    @Override
    public String localNodeId() {
        return nodeId;
    }

    @Override
    public boolean claim(String viewId, String sessionId, Instant now, Instant expiresAt) {
        validate(viewId, now, expiresAt);
        var lease = new LiveViewOwner(nodeId, sessionId, expiresAt);
        var result = leases.compute(viewId, (ignored, current) ->
                current == null || !current.expiresAt().isAfter(now)
                        || current.nodeId().equals(nodeId) && current.sessionId().equals(sessionId)
                        ? lease : current);
        return result.nodeId().equals(nodeId);
    }

    @Override
    public boolean renew(String viewId, String sessionId, Instant now, Instant expiresAt) {
        validate(viewId, now, expiresAt);
        var renewed = new AtomicBoolean();
        leases.computeIfPresent(viewId, (ignored, current) -> {
            if (!current.expiresAt().isAfter(now)
                    || !current.nodeId().equals(nodeId)
                    || !current.sessionId().equals(sessionId)) {
                return current;
            }
            renewed.set(true);
            return current.expiresAt().isBefore(expiresAt)
                    ? new LiveViewOwner(nodeId, sessionId, expiresAt)
                    : current;
        });
        return renewed.get();
    }

    @Override
    public Optional<LiveViewOwner> find(String viewId, Instant now) {
        validateViewId(viewId);
        Objects.requireNonNull(now, "now");
        var result = leases.computeIfPresent(viewId, (ignored, current) ->
                current.expiresAt().isAfter(now) ? current : null);
        return Optional.ofNullable(result);
    }

    @Override
    public void release(String viewId, String sessionId) {
        validateViewId(viewId);
        Objects.requireNonNull(sessionId, "sessionId");
        leases.computeIfPresent(viewId, (ignored, current) ->
                current.nodeId().equals(nodeId) && current.sessionId().equals(sessionId) ? null : current);
    }

    @Override
    public void deleteExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        leases.forEach((viewId, ignored) -> leases.computeIfPresent(viewId,
                (key, current) -> current.expiresAt().isAfter(now) ? current : null));
    }

    @Override
    public void close() {
        leases.clear();
    }

    private static void validate(String viewId, Instant now, Instant expiresAt) {
        validateViewId(viewId);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Live-view lease expiry must be after the current instant");
        }
    }

    private static void validateViewId(String viewId) {
        Objects.requireNonNull(viewId, "viewId");
        if (viewId.isBlank() || viewId.length() > 512 || viewId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("View id must be nonblank, control-free, and at most 512 characters");
        }
    }

    private static String generatedNodeId() {
        var bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
