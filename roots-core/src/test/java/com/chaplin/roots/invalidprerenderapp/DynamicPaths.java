package com.chaplin.roots.invalidprerenderapp;

import com.chaplin.roots.StaticPathProvider;

import java.util.List;
import java.util.Map;

public final class DynamicPaths implements StaticPathProvider {
    public enum Mode { RESERVED, DUPLICATE, SHADOWED }

    private static volatile Mode mode = Mode.RESERVED;

    public static void mode(Mode next) {
        mode = next;
    }

    @Override
    public List<Map<String, String>> paths() {
        return switch (mode) {
            case RESERVED -> List.of(Map.of("slug", "_roots"));
            case DUPLICATE -> List.of(Map.of("slug", "same"), Map.of("slug", "same"));
            case SHADOWED -> List.of(Map.of("slug", "fixed"));
        };
    }
}
