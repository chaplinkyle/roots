package dev.roots.spring.boot;

import dev.roots.RootsConfig;

/** Customizes the Roots configuration after Spring Boot properties are applied. */
@FunctionalInterface
public interface RootsConfigCustomizer {
    /**
     * Adds application-specific middleware, policies, caches, or other settings.
     *
     * @param builder configuration builder owned by the starter
     */
    void customize(RootsConfig.Builder builder);
}
