package dev.roots;

import java.util.Objects;
import java.util.function.Supplier;

public final class ContextKey<T> {
    private final String name;
    private final Supplier<? extends T> defaultValue;

    private ContextKey(String name, Supplier<? extends T> defaultValue) {
        this.name = Objects.requireNonNull(name);
        this.defaultValue = Objects.requireNonNull(defaultValue);
    }

    public static <T> ContextKey<T> of(String name, Supplier<? extends T> defaultValue) {
        return new ContextKey<>(name, defaultValue);
    }

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
