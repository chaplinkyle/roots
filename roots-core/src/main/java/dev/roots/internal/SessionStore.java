package dev.roots.internal;

import com.sun.net.httpserver.HttpExchange;
import dev.roots.Session;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class SessionStore {
    static final String COOKIE = "ROOTS_SESSION";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();
    private final long timeoutMillis;

    SessionStore(Duration timeout) {
        timeoutMillis = timeout.toMillis();
    }

    ResolvedSession resolve(HttpExchange exchange) {
        var requestedId = cookie(exchange, COOKIE);
        var now = System.currentTimeMillis();
        if (requestedId != null) {
            var stored = sessions.get(requestedId);
            if (stored != null && now - stored.lastAccess() <= timeoutMillis) {
                stored.touch(now);
                return new ResolvedSession(stored.session(), false);
            }
        }
        var id = token();
        var session = new Session(id);
        sessions.put(id, new StoredSession(session, now));
        return new ResolvedSession(session, true);
    }

    void cleanup() {
        var cutoff = System.currentTimeMillis() - timeoutMillis;
        sessions.entrySet().removeIf(entry -> entry.getValue().lastAccess() < cutoff);
    }

    static String token() {
        var bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String cookie(HttpExchange exchange, String name) {
        var cookies = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookies == null) {
            return null;
        }
        for (var value : cookies.split(";")) {
            var pair = value.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals(name)) {
                return pair[1];
            }
        }
        return null;
    }

    record ResolvedSession(Session session, boolean isNew) {
        String setCookieHeader() {
            return COOKIE + "=" + session.id() + "; Path=/; HttpOnly; SameSite=Lax";
        }
    }

    private static final class StoredSession {
        private final Session session;
        private volatile long lastAccess;

        private StoredSession(Session session, long lastAccess) {
            this.session = session;
            this.lastAccess = lastAccess;
        }

        Session session() {
            return session;
        }

        long lastAccess() {
            return lastAccess;
        }

        void touch(long now) {
            lastAccess = now;
        }
    }
}
