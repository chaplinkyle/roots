package dev.roots;

import java.util.Objects;

/**
 * One generated static route.
 *
 * @param path concrete application path
 * @param resource classpath resource containing the generated HTML
 * @param revalidateSeconds zero or a positive regeneration interval
 */
public record PrerenderedRoute(String path, String resource, long revalidateSeconds) {
    /** Validates generated-route metadata. */
    public PrerenderedRoute {
        path = Objects.requireNonNull(path, "path");
        resource = Objects.requireNonNull(resource, "resource");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Prerendered paths must start with '/': " + path);
        }
        if (resource.isBlank()) {
            throw new IllegalArgumentException("Prerendered resources cannot be blank");
        }
        if (revalidateSeconds < 0) {
            throw new IllegalArgumentException("Prerender revalidation cannot be negative");
        }
    }
}
