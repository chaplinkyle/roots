package dev.roots;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class PageContext {
    private final String path;
    private final Map<String, String> parameters;
    private final Map<String, List<String>> query;
    private final Session session;
    private final Map<ContextKey<?>, Object> contextValues = new ConcurrentHashMap<>();
    private volatile Consumer<Runnable> updater;

    public PageContext(
            String path,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Session session
    ) {
        this.path = path;
        this.parameters = Map.copyOf(parameters);
        this.query = Map.copyOf(query);
        this.session = session;
    }

    public String path() {
        return path;
    }

    public String parameter(String name) {
        var value = parameters.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Unknown route parameter: " + name);
        }
        return value;
    }

    public Map<String, String> parameters() {
        return parameters;
    }

    public Optional<String> query(String name) {
        return query.getOrDefault(name, List.of()).stream().findFirst();
    }

    public List<String> queryValues(String name) {
        return query.getOrDefault(name, List.of());
    }

    public Session session() {
        return session;
    }

    public <T> void provide(ContextKey<T> key, T value) {
        if (value == null) {
            contextValues.remove(key);
        } else {
            contextValues.put(key, value);
        }
    }

    public <T> T context(ContextKey<T> key) {
        var value = contextValues.get(key);
        if (value == null) {
            return key.defaultValue();
        }
        @SuppressWarnings("unchecked")
        var typed = (T) value;
        return typed;
    }

    /** Safely mutate a mounted live view from a background or virtual thread. */
    public void update(Runnable mutation) {
        var currentUpdater = updater;
        if (currentUpdater == null) {
            throw new IllegalStateException("This page is not attached to a live browser view");
        }
        currentUpdater.accept(mutation);
    }

    public void attachUpdater(Consumer<Runnable> updater) {
        if (this.updater != null) {
            throw new IllegalStateException("A PageContext can only be attached once");
        }
        this.updater = updater;
    }
}
