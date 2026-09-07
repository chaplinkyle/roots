package com.chaplin.roots.internal;

import com.chaplin.roots.Session;
import java.util.Map;
import java.util.Optional;

/** Sentinel that cannot accidentally turn session IDs into shared machine identity. */
final class StatelessSession extends Session {
    static final Session INSTANCE = new StatelessSession();
    private StatelessSession() { super("stateless"); }
    private IllegalStateException unavailable() {
        return new IllegalStateException("Browser session access is unavailable on a @Stateless API route");
    }
    @Override public String id() { throw unavailable(); }
    @Override public Optional<Object> get(String key) { throw unavailable(); }
    @Override public void put(String key, Object value) { throw unavailable(); }
    @Override public void remove(String key) { throw unavailable(); }
    @Override public Map<String, Object> snapshot() { throw unavailable(); }
}
