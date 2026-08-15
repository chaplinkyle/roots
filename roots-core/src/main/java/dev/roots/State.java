package dev.roots;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** Retained component state. Mutations made during a server action are visible on the next render. */
public final class State<T> {
    private T value;

    private State(T initialValue) {
        value = initialValue;
    }

    public static <T> State<T> of(T initialValue) {
        return new State<>(initialValue);
    }

    public synchronized T get() {
        return value;
    }

    public synchronized void set(T value) {
        this.value = value;
    }

    public synchronized T update(UnaryOperator<T> update) {
        value = Objects.requireNonNull(update, "update").apply(value);
        return value;
    }
}
