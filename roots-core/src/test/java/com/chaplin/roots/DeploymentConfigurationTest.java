package com.chaplin.roots;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DeploymentConfigurationTest {
    @Test
    void appliesExplicitEnvironmentBeforeCommandLineOverrides() {
        var config = RootsConfig.forApplication(DeploymentConfigurationTest.class).environment(Map.ofEntries(
                Map.entry("ROOTS_HOST", "0.0.0.0"), Map.entry("ROOTS_PORT", "9090"),
                Map.entry("ROOTS_DEVELOPMENT", "false"), Map.entry("ROOTS_SECURE_COOKIES", "true"),
                Map.entry("ROOTS_MAX_LIVE_VIEWS", "100"), Map.entry("ROOTS_MAX_CONCURRENT_REQUESTS", "300"),
                Map.entry("ROOTS_MAX_SESSIONS", "1000"), Map.entry("ROOTS_VIEW_TIMEOUT", "PT10M"),
                Map.entry("ROOTS_SESSION_TIMEOUT", "PT4H"), Map.entry("ROOTS_MAX_REQUEST_BYTES", "2000000"),
                Map.entry("ROOTS_REQUEST_BODY_MEMORY_THRESHOLD", "4096"),
                Map.entry("ROOTS_MAX_MULTIPART_TEXT_FIELD_BYTES", "1024"),
                Map.entry("ROOTS_REQUEST_BODY_TEMPORARY_DIRECTORY", System.getProperty("java.io.tmpdir")),
                Map.entry("ROOTS_TRUSTED_PROXIES", "127.0.0.1/32, ::1/128"),
                Map.entry("UNRELATED_SECRET", "must not be consumed")))
                .arguments("--port=8088").build();
        assertEquals(8088, config.port());
        assertEquals("0.0.0.0", config.host());
        assertFalse(config.development());
        assertTrue(config.secureCookies());
        assertEquals(100, config.maxLiveViews());
        assertEquals(300, config.maxConcurrentRequests());
        assertEquals(1000, config.maxSessions());
        assertEquals(Duration.ofMinutes(10), config.viewTimeout());
        assertEquals(Duration.ofHours(4), config.sessionTimeout());
        assertEquals(2_000_000, config.maxRequestBytes());
        assertEquals(4096, config.requestBodyPolicy().memoryThreshold());
        assertEquals(1024, config.requestBodyPolicy().maxMultipartTextFieldBytes());
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(),
                config.requestBodyPolicy().temporaryDirectory());
    }

    @Test
    void rejectsMalformedSettingsWithoutEchoingTheirValues() {
        for (var name : new String[]{"ROOTS_DEVELOPMENT", "ROOTS_PORT", "ROOTS_VIEW_TIMEOUT",
                "ROOTS_SECURE_COOKIES", "ROOTS_TRUSTED_PROXIES"}) {
            var error = assertThrows(IllegalArgumentException.class, () ->
                    RootsConfig.forApplication(getClass()).environment(Map.of(name, "secret-invalid-value")));
            assertEquals("Invalid deployment setting " + name, error.getMessage());
            assertNull(error.getCause());
        }
        assertThrows(IllegalArgumentException.class, () ->
                RootsConfig.forApplication(getClass()).environment(Map.of("ROOTS_HOST", " ")));
        assertThrows(IllegalArgumentException.class, () ->
                RootsConfig.forApplication(getClass()).environment(Map.of("ROOTS_MAX_LIVE_VIEWS", "-1")).build());
        assertThrows(IllegalArgumentException.class, () ->
                Roots.run(RootsConfig.forApplication(getClass()).build(), Duration.ofHours(2)));
    }
}
