package com.chaplin.roots;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable request head evaluated before body parsing or session allocation.
 *
 * @param method uppercase HTTP method
 * @param path transport-relative request path
 * @param connection trusted client connection details
 */
public record RateLimitRequest(String method, String path, ClientConnection connection) {
    /** Validates the rate-limit input. */
    public RateLimitRequest {
        method = Objects.requireNonNull(method, "method").toUpperCase(Locale.ROOT);
        path = Objects.requireNonNull(path, "path");
        connection = Objects.requireNonNull(connection, "connection");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Rate-limit path must be absolute");
        }
    }
}
