package dev.roots;

import java.util.Objects;
import java.util.function.Supplier;

/** A tiny dependency-keyed cache for expensive component calculations. */
public final class Memo<T> {
    private Object dependencies;
    private T value;
    private boolean initialized;

    public synchronized T compute(Object dependencies, Supplier<? extends T> calculation) {
        if (!initialized || !Objects.equals(this.dependencies, dependencies)) {
            value = calculation.get();
            this.dependencies = dependencies;
            initialized = true;
        }
        return value;
    }

    public synchronized void clear() {
        dependencies = null;
        value = null;
        initialized = false;
    }
}
