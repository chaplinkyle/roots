package dev.roots.jdbc;

/**
 * Encodes values stored by {@link JdbcRootsCache}.
 *
 * <p>Implementations must be thread-safe and use a stable, explicitly
 * versioned representation. Java object serialization is intentionally not
 * part of this boundary.</p>
 */
public interface JdbcCacheValueCodec {
    /** Encodes one non-null cache value.
     * @param value application value
     * @return non-null stable representation
     */
    String encode(Object value);

    /** Decodes one value as the type requested by the cache caller.
     * @param encoded stored representation
     * @param type requested runtime type
     * @param <T> requested type
     * @return non-null decoded value
     */
    <T> T decode(String encoded, Class<T> type);

    /**
     * Returns the safe built-in scalar codec.
     *
     * <p>It preserves the scalar types supported by
     * {@link JdbcSessionValueCodec#standard()} and rejects all other values.
     * Applications can supply a JSON or domain-specific codec for records and
     * collections.</p>
     *
     * @return thread-safe scalar codec
     */
    static JdbcCacheValueCodec standard() {
        return StandardJdbcCacheValueCodec.INSTANCE;
    }
}
