package com.chaplin.roots;

import java.util.Objects;
import java.util.function.Supplier;

/** A tiny dependency-keyed cache for expensive component calculations.
 * @param <T> calculated value type */
public final class Memo<T> {
    private Object dependencies;
    private T value;
    private boolean initialized;

    /** Creates an empty memo. */
    public Memo() {
    }

    /** Computes once and reuses the value while dependencies remain equal.
     * @param dependencies equality-compared dependency key
     * @param calculation value supplier
     * @return cached or newly calculated value */
    public synchronized T compute(Object dependencies, Supplier<? extends T> calculation) {
        if (!initialized || !Objects.equals(this.dependencies, dependencies)) {
            value = calculation.get();
            this.dependencies = dependencies;
            initialized = true;
        }
        return value;
    }

    /** Clears the cached dependency key and value. */
    public synchronized void clear() {
        dependencies = null;
        value = null;
        initialized = false;
    }
}
