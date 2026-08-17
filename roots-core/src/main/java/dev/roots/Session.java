package dev.roots;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

/**
 * Thread-safe server-side values associated with one browser session.
 *
 * <p>External {@link SessionRepository} implementations may subclass this type
 * to delegate value operations to a durable or distributed store.</p>
 */
public class Session {
    private final String id;
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    /** Creates a session.
     * @param id nonblank cookie-safe session identifier of at most 512 characters */
    public Session(String id) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.isEmpty() || id.length() > 512) {
            throw new IllegalArgumentException("Session id must be a nonblank cookie-safe value of at most 512 characters");
        }
        try {
            CookieSyntax.requireValue(id);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Session id must be a nonblank cookie-safe value of at most 512 characters",
                    exception
            );
        }
    }

    /** Returns the session identifier.
     * @return identifier */
    public String id() {
        return id;
    }

    /** Finds an untyped session value.
     * @param key value key
     * @return value, if present */
    public Optional<Object> get(String key) {
        return Optional.ofNullable(values.get(Objects.requireNonNull(key, "key")));
    }

    /** Finds a session value of the requested type.
     * @param <T> value type
     * @param key value key
     * @param type required runtime type
     * @return typed value, if present and compatible */
    public <T> Optional<T> get(String key, Class<T> type) {
        return get(key).filter(type::isInstance).map(type::cast);
    }

    /** Stores or removes a session value.
     * @param key value key
     * @param value value, or {@code null} to remove */
    public void put(String key, Object value) {
        Objects.requireNonNull(key, "key");
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    /** Removes a session value.
     * @param key value key */
    public void remove(String key) {
        values.remove(Objects.requireNonNull(key, "key"));
    }

    /** Returns a point-in-time immutable copy of all values.
     * @return session values */
    public Map<String, Object> snapshot() {
        return Map.copyOf(values);
    }
}
