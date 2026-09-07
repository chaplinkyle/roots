package com.chaplin.roots.compatibility;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class PublicApiCompatibilityTest {
    @Test
    void publicCoreApiMatchesTheReviewedBaseline() throws IOException {
        try (var input = PublicApiCompatibilityTest.class.getResourceAsStream("/com/chaplin/roots/public-api-v1.txt")) {
            assertNotNull(input, "Missing public API baseline");
            var expected = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(expected, PublicApiSnapshot.capture(),
                    "Public API changed. Review compatibility and migration impact, then deliberately update "
                            + "public-api-v1.txt with PublicApiSnapshot.");
        }
    }
}
