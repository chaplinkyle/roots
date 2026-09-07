package com.chaplin.roots;

import java.util.Objects;
import java.util.function.Supplier;

/** A typed key for values propagated through a page's component tree.
 * @param <T> value type */
public final class ContextKey<T> {
    private final String name;
    private final Supplier<? extends T> defaultValue;

    private ContextKey(String name, Supplier<? extends T> defaultValue) {
        this.name = Objects.requireNonNull(name);
        this.defaultValue = Objects.requireNonNull(defaultValue);
    }

    /** Creates a key with a lazy default value.
     * @param <T> value type
     * @param name diagnostic key name
     * @param defaultValue default supplier
     * @return context key */
    public static <T> ContextKey<T> of(String name, Supplier<? extends T> defaultValue) {
        return new ContextKey<>(name, defaultValue);
    }

    /** Creates a key that fails if no value has been provided.
     * @param <T> value type
     * @param name diagnostic key name
     * @return required context key */
    public static <T> ContextKey<T> required(String name) {
        return new ContextKey<>(name, () -> {
            throw new IllegalStateException("No value was provided for Roots context '" + name + "'");
        });
    }

    T defaultValue() {
        return defaultValue.get();
    }

    @Override
    public String toString() {
        return "ContextKey[" + name + "]";
    }
}
