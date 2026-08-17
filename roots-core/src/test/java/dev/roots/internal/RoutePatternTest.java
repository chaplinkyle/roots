package dev.roots.internal;

import dev.roots.RoutePaths;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RoutePatternTest {
    @Test
    void derivesStaticDynamicAndGroupedPageRoutes() {
        var route = RoutePattern.fromPackage(
                "com.acme.pages.group_admin.customers.$customerId",
                "com.acme.pages",
                ""
        );

        assertEquals("/customers/{customerId}", route.display());
        assertEquals("42", route.match("/customers/42").orElseThrow().get("customerId"));
        assertFalse(route.match("/customers").isPresent());
    }

    @Test
    void includesTheApiPrefixInMatching() {
        var route = RoutePattern.fromPackage("com.acme.api.health", "com.acme.api", "/api");

        assertTrue(route.match("/api/health").isPresent());
        assertFalse(route.match("/health").isPresent());
    }

    @Test
    void supportsAnnotatedTemplatesAndCatchAlls() {
        var route = RoutePattern.fromTemplate("/files/{*path}");

        assertEquals("docs/start.html", route.match("/files/docs/start.html").orElseThrow().get("path"));
    }

    @Test
    void sharesCanonicalPathsAndAmbiguityShapesWithBuildTooling() {
        assertEquals("/customer-files/{customerId}", RoutePaths.fromPackage(
                "com.acme.pages.group_admin.customer_files.$customerId",
                "com.acme.pages",
                ""
        ));
        assertEquals("/users/{id}", RoutePaths.fromTemplate("/users/{id}/"));
        assertEquals("/users/{}", RoutePaths.shape("/users/{id}"));
        assertEquals("/files/{*}", RoutePaths.shape("/files/{*path}"));
        assertThrows(IllegalArgumentException.class,
                () -> RoutePaths.fromPackage("app.pages.$9id", "app.pages", ""));
        assertThrows(IllegalArgumentException.class,
                () -> RoutePaths.fromTemplate("/users/{id}/orders/{id}"));
    }

    @Test
    void expandsStaticParametersIntoEncodedConcretePaths() {
        var dynamic = RoutePattern.fromTemplate("/products/{productId}");
        var catchAll = RoutePattern.fromTemplate("/docs/{*path}");

        assertEquals(java.util.List.of("productId"), dynamic.parameterNames());
        assertEquals("/products/north%20star", dynamic.expand(Map.of("productId", "north star")));
        assertEquals("/products/a%2Bb", dynamic.expand(Map.of("productId", "a+b")));
        assertEquals("/docs/guides/start%20here", catchAll.expand(Map.of("path", "guides/start here")));
        assertThrows(IllegalArgumentException.class, () -> dynamic.expand(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> dynamic.expand(Map.of("productId", "one", "extra", "two")));
        assertThrows(IllegalArgumentException.class, () -> catchAll.expand(Map.of("path", "guides//start")));
    }
}
