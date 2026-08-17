package dev.roots.jdbc;

/**
 * Reports a database or stored-value failure from {@link JdbcRootsCache}.
 */
public final class JdbcCacheException extends RuntimeException {
    /** Creates a cache failure.
     * @param message actionable failure description
     * @param cause underlying failure
     */
    public JdbcCacheException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Creates a cache failure without an underlying exception.
     * @param message actionable failure description
     */
    public JdbcCacheException(String message) {
        super(message);
    }
}
