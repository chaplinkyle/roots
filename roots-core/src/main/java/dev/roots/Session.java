package dev.roots;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class Session {
    private final String id;
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    public Session(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public Optional<Object> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        return get(key).filter(type::isInstance).map(type::cast);
    }

    public void put(String key, Object value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    public void remove(String key) {
        values.remove(key);
    }

    public Map<String, Object> snapshot() {
        return Map.copyOf(values);
    }
}
