package dev.roots.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
