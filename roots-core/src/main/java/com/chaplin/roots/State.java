package com.chaplin.roots;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** Retained component state. Mutations made during a server action are visible on the next render.
 * @param <T> stored value type */
public final class State<T> {
    private T value;

    private State(T initialValue) {
        value = initialValue;
    }

    /** Creates retained state.
     * @param <T> stored value type
     * @param initialValue initial value
     * @return state holder */
    public static <T> State<T> of(T initialValue) {
        return new State<>(initialValue);
    }

    /** Returns the current value.
     * @return current value */
    public synchronized T get() {
        return value;
    }

    /** Replaces the current value.
     * @param value new value */
    public synchronized void set(T value) {
        this.value = value;
    }

    /** Atomically transforms the current value.
     * @param update transformation
     * @return updated value */
    public synchronized T update(UnaryOperator<T> update) {
        value = Objects.requireNonNull(update, "update").apply(value);
        return value;
    }
}
