package com.chaplin.roots.jdbc;

import java.util.Objects;

enum StandardJdbcCacheValueCodec implements JdbcCacheValueCodec {
    INSTANCE;

    private final JdbcSessionValueCodec delegate = JdbcSessionValueCodec.standard();

    @Override
    public String encode(Object value) {
        return delegate.encode(value);
    }

    @Override
    public <T> T decode(String encoded, Class<T> type) {
        Objects.requireNonNull(type, "type");
        var value = Objects.requireNonNull(delegate.decode(encoded), "Decoded JDBC cache value");
        if (!type.isInstance(value)) {
            throw new IllegalStateException("Stored JDBC cache value contains "
                    + value.getClass().getName() + " instead of " + type.getName());
        }
        return type.cast(value);
    }
}
