package dev.roots.jdbc;

/**
 * Encodes values stored by {@link JdbcSessionRepository}.
 *
 * <p>Implementations must be thread-safe and must return non-null strings and
 * values. Encoded strings are stored in a bounded SQL {@code VARCHAR} column.
 * Applications can use this boundary for a JSON codec or another stable,
 * explicitly versioned representation.</p>
 */
public interface JdbcSessionValueCodec {
    /** Encodes one non-null session value.
     * @param value application value
     * @return non-null stable representation
     */
    String encode(Object value);

    /** Decodes one non-null stored representation.
     * @param encoded stored representation
     * @return non-null application value
     */
    Object decode(String encoded);

    /**
     * Returns the safe built-in scalar codec.
     *
     * <p>It preserves strings, booleans, primitive wrapper numbers, characters,
     * big integers/decimals, UUIDs, instants, and byte arrays. Other types are
     * rejected rather than Java-serialized.</p>
     *
     * @return thread-safe scalar codec
     */
    static JdbcSessionValueCodec standard() {
        return StandardJdbcSessionValueCodec.INSTANCE;
    }
}
