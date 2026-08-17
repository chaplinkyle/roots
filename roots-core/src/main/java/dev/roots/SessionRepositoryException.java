package dev.roots;

/** Signals that an external session repository could not complete an operation. */
public class SessionRepositoryException extends RuntimeException {
    /** Creates a repository failure.
     * @param message failure description */
    public SessionRepositoryException(String message) {
        super(message);
    }

    /** Creates a repository failure with its cause.
     * @param message failure description
     * @param cause underlying failure */
    public SessionRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
