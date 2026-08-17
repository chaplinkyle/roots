package dev.roots.spring.boot.compatibility;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class AdapterPublicApiCompatibilityTest {
    @Test
    void publicAdapterApisMatchTheReviewedBaseline() throws IOException {
        try (var input = AdapterPublicApiCompatibilityTest.class
                .getResourceAsStream("/dev/roots/adapter-public-api-v1.txt")) {
            assertNotNull(input, "Missing adapter public API baseline");
            var expected = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(expected, AdapterPublicApiSnapshot.capture(),
                    "Servlet, Spring, or Spring Boot public API changed. Review compatibility and migration impact, "
                            + "then deliberately update adapter-public-api-v1.txt with AdapterPublicApiSnapshot.");
        }
    }
}
