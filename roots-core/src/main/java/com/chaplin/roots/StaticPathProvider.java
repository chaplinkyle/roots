package com.chaplin.roots;

import java.util.List;
import java.util.Map;

/** Supplies concrete parameter sets for prerendering a dynamic page route. */
@FunctionalInterface
public interface StaticPathProvider {
    /** Returns immutable-compatible parameter maps keyed by route parameter name.
     * @return concrete route parameter sets
     */
    List<Map<String, String>> paths();

    /** Sentinel used when a static route does not need a path provider. */
    final class None implements StaticPathProvider {
        /** Creates the sentinel provider. */
        public None() {
        }

        @Override
        public List<Map<String, String>> paths() {
            return List.of();
        }
    }
}
