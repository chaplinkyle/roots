package com.chaplin.roots;

/** Signals transient unavailability of a live-view ownership registry. */
public final class LiveViewOwnershipException extends RuntimeException {
    /** Creates an availability failure.
     * @param message safe diagnostic message */
    public LiveViewOwnershipException(String message) {
        super(message);
    }

    /** Creates an availability failure with its cause.
     * @param message safe diagnostic message
     * @param cause underlying failure */
    public LiveViewOwnershipException(String message, Throwable cause) {
        super(message, cause);
    }
}
