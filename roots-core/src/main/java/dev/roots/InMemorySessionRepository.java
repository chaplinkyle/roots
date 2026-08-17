package dev.roots;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class InMemorySessionRepository implements SessionRepository {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();

    @Override
    public Optional<Session> findAndTouch(String id, Instant accessedAt, Instant expiresAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accessedAt, "accessedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(accessedAt)) {
            throw new IllegalArgumentException("New session expiry must be after access time");
        }
        var stored = sessions.computeIfPresent(id, (ignored, current) -> {
            if (current.expiresAt().isAfter(accessedAt)) {
                return new StoredSession(current.session(), expiresAt);
            }
            return null;
        });
        return stored == null ? Optional.empty() : Optional.of(stored.session());
    }

    @Override
    public synchronized Optional<Session> create(Instant expiresAt, int maximumSessions) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (maximumSessions < 1) {
            throw new IllegalArgumentException("Maximum sessions must be positive");
        }
        if (sessions.size() >= maximumSessions) {
            return Optional.empty();
        }
        while (true) {
            var session = new Session(token());
            if (sessions.putIfAbsent(session.id(), new StoredSession(session, expiresAt)) == null) {
                return Optional.of(session);
            }
        }
    }

    @Override
    public void deleteExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        sessions.forEach((id, ignored) -> sessions.computeIfPresent(id, (key, current) ->
                current.expiresAt().isAfter(now) ? current : null));
    }

    @Override
    public int size() {
        return sessions.size();
    }

    @Override
    public void close() {
        sessions.clear();
    }

    private static String token() {
        var bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record StoredSession(Session session, Instant expiresAt) {
    }
}
