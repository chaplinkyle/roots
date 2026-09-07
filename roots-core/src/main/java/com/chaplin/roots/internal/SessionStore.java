package com.chaplin.roots.internal;

import com.chaplin.roots.Session;
import com.chaplin.roots.SessionRepository;
import com.chaplin.roots.ResponseCookie;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

final class SessionStore {
    static final String COOKIE = "ROOTS_SESSION";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionRepository repository;
    private final Duration timeout;
    private final boolean secureCookies;
    private final int maxSessions;

    SessionStore(SessionRepository repository, Duration timeout, boolean secureCookies, int maxSessions) {
        this.repository = repository;
        this.timeout = timeout;
        this.secureCookies = secureCookies;
        this.maxSessions = maxSessions;
    }

    ResolvedSession resolve(TransportExchange exchange) {
        var cookiePath = cookiePath(exchange);
        var existing = resolveExisting(exchange);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        var now = Instant.now();
        var session = repository.create(now.plus(timeout), maxSessions)
                .orElseThrow(CapacityExceededException::new);
        return new ResolvedSession(session, true, secureCookies, cookiePath);
    }

    Optional<ResolvedSession> resolveExisting(TransportExchange exchange) {
        var requestedId = cookie(exchange, COOKIE);
        if (requestedId == null) {
            return Optional.empty();
        }
        var now = Instant.now();
        return repository.findAndTouch(requestedId, now, now.plus(timeout))
                .map(session -> {
                    if (!session.id().equals(requestedId)) {
                        throw new IllegalStateException("Session repository returned a different identifier");
                    }
                    return new ResolvedSession(session, false, secureCookies, cookiePath(exchange));
                });
    }

    void cleanup() {
        repository.deleteExpired(Instant.now());
    }

    int size() {
        return repository.size();
    }

    void clear() {
        repository.close();
    }

    static String token() {
        var bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String cookie(TransportExchange exchange, String name) {
        var cookies = exchange.requestHeader("Cookie");
        if (cookies == null || cookies.length() > 16_384) {
            return null;
        }
        var count = 0;
        for (var value : cookies.split(";", -1)) {
            if (++count > 128) return null;
            var pair = value.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals(name) && validSessionId(pair[1])) {
                return pair[1];
            }
        }
        return null;
    }

    private static boolean validSessionId(String value) {
        return !value.isEmpty() && value.length() <= 512 && value.chars().allMatch(character ->
                character == 0x21
                        || character >= 0x23 && character <= 0x2b
                        || character >= 0x2d && character <= 0x3a
                        || character >= 0x3c && character <= 0x5b
                        || character >= 0x5d && character <= 0x7e
        );
    }

    private static String cookiePath(TransportExchange exchange) {
        var mountPath = exchange.mountPath();
        if (!validMountPath(mountPath)) {
            throw new IllegalArgumentException("Transport mount path is not normalized");
        }
        return mountPath.isEmpty() ? "/" : mountPath;
    }

    private static boolean validMountPath(String value) {
        return value != null && (value.isEmpty()
                || value.matches("/[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*")
                && java.util.Arrays.stream(value.substring(1).split("/"))
                .noneMatch(segment -> segment.equals(".") || segment.equals("..")));
    }

    record ResolvedSession(Session session, boolean isNew, boolean secure, String path) {
        String setCookieHeader() {
            return ResponseCookie.builder(COOKIE, session.id())
                    .path(path)
                    .secure(secure)
                    .build()
                    .headerValue();
        }
    }

    static final class CapacityExceededException extends RuntimeException {
    }

}
