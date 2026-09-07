package com.chaplin.roots;

/**
 * Creates the application configuration used by build-time prerendering.
 *
 * <p>Applications only need this SPI when generated pages require a custom
 * {@link InstanceFactory}, cache, or other application-owned configuration.
 * The Maven plugin otherwise uses {@link RootsConfig#forApplication(Class)}.</p>
 */
@FunctionalInterface
public interface PrerenderConfigFactory {
    /**
     * Creates a production-mode configuration for the application being built.
     *
     * @param applicationClass application convention anchor loaded from the project
     * @return configuration used to enumerate and render static pages
     */
    RootsConfig create(Class<?> applicationClass);
}
