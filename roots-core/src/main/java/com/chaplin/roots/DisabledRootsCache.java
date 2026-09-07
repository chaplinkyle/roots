package com.chaplin.roots;

import java.util.Objects;
import java.util.function.Supplier;

enum DisabledRootsCache implements RootsCache {
    INSTANCE;

    @Override
    public <T> T get(String key, Class<T> type, CachePolicy policy, Supplier<? extends T> loader) {
        CacheValidation.key(key);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(policy, "policy");
        var value = Objects.requireNonNull(Objects.requireNonNull(loader, "loader").get(),
                "Cache loaders must return a value");
        return type.cast(value);
    }

    @Override
    public boolean invalidate(String key) {
        CacheValidation.key(key);
        return false;
    }

    @Override
    public long invalidateTag(String tag) {
        CacheValidation.tag(tag);
        return 0;
    }

    @Override
    public void clear() {
    }

    @Override
    public CacheSnapshot snapshot() {
        return new CacheSnapshot(false, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
