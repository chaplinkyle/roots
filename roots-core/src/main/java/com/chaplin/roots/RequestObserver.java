package com.chaplin.roots;

import java.util.Objects;

/** Receives one immutable event after each Roots HTTP exchange ends. */
@FunctionalInterface
public interface RequestObserver {
    /** Handles a completed request without changing its response.
     * @param observation immutable completion event
     */
    void onComplete(RequestObservation observation);

    /** Creates an INFO-level JSON-lines observer using the supplied JDK logger.
     * @param logger destination logger
     * @return structured request observer
     */
    static RequestObserver structured(System.Logger logger) {
        Objects.requireNonNull(logger, "logger");
        return observation -> logger.log(System.Logger.Level.INFO, observation.json());
    }

    /** Creates an INFO-level JSON-lines observer named {@code com.chaplin.roots.requests}.
     * @return structured request observer
     */
    static RequestObserver structured() {
        return structured(System.getLogger("com.chaplin.roots.requests"));
    }
}
