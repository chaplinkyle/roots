package dev.roots;

import dev.roots.internal.PrerenderEngine;

import java.nio.file.Path;
import java.util.Objects;

/** Generates deterministic static HTML resources for {@code @Prerender} pages. */
public final class Prerenderer {
    private Prerenderer() {
    }

    /** Generates resources and an application-specific prerender manifest.
     * @param config application configuration used to discover and construct pages
     * @param outputDirectory destination classes/resources directory
     * @return generation report
     */
    public static PrerenderReport generate(RootsConfig config, Path outputDirectory) {
        return PrerenderEngine.generate(
                Objects.requireNonNull(config, "config"),
                Objects.requireNonNull(outputDirectory, "outputDirectory")
        );
    }
}
