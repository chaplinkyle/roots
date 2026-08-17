package dev.roots;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Result of generating static page resources.
 *
 * @param outputDirectory destination classes/resources directory
 * @param routes generated concrete routes
 */
public record PrerenderReport(Path outputDirectory, List<PrerenderedRoute> routes) {
    /** Defensively freezes the report. */
    public PrerenderReport {
        outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory")
                .toAbsolutePath().normalize();
        routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
    }
}
