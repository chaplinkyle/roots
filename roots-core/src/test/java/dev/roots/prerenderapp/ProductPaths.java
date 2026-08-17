package dev.roots.prerenderapp;

import dev.roots.StaticPathProvider;

import java.util.List;
import java.util.Map;

public final class ProductPaths implements StaticPathProvider {
    @Override
    public List<Map<String, String>> paths() {
        return List.of(
                Map.of("productId", "north star"),
                Map.of("productId", "a+b")
        );
    }
}
