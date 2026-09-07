package com.chaplin.roots;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ProblemDetailTest {
    @Test
    void emitsAConsistentBoundedStandardResponse() {
        var response = ProblemDetail.of(URI.create("https://example.com/problems/conflict"),
                409, "Conflict", "Another user saved this record.\nTry again.")
                .withInstance(URI.create("/requests/123")).withExtension("traceId", "abc").response();
        assertEquals(409, response.status());
        assertEquals("application/problem+json", response.headers().get("Content-Type").getFirst());
        assertEquals("no-store", response.headers().get("Cache-Control").getFirst());
        assertEquals("{\"type\":\"https://example.com/problems/conflict\",\"status\":409,\"title\":\"Conflict\","
                + "\"detail\":\"Another user saved this record.\\u000aTry again.\",\"instance\":\"/requests/123\",\"traceId\":\"abc\"}",
                response.bodyText());
    }

    @Test
    void rejectsReservedExtensionsAndCopiesCallerState() {
        var extensions = new HashMap<>(Map.of("code", "conflict"));
        var problem = new ProblemDetail(URI.create("urn:example:conflict"), 409, "Conflict", "", null, extensions);
        extensions.put("code", "changed");
        assertEquals("conflict", problem.extensions().get("code"));
        assertThrows(IllegalArgumentException.class, () -> problem.withExtension("status", "200"));
        assertThrows(IllegalArgumentException.class, () -> problem.withExtension("bad-key", "value"));
        assertThrows(IllegalArgumentException.class,
                () -> ProblemDetail.of(URI.create("relative"), 409, "Conflict", ""));
        assertThrows(IllegalArgumentException.class,
                () -> ProblemDetail.of(URI.create("urn:example:error"), 200, "Conflict", ""));
        assertThrows(IllegalArgumentException.class,
                () -> ProblemDetail.of(URI.create("urn:example:error"), 400, "", ""));
        assertThrows(IllegalArgumentException.class, () -> problem.withExtension("code", "x".repeat(2049)));
    }

    @Test
    void quotesControlCharactersBackslashesAndUnicodeWithoutInjectingJson() {
        var response = ProblemDetail.of(URI.create("urn:example:error"), 400, "Invalid",
                "\"\\\u0000\t\ud83c\udf31").response();
        assertTrue(response.bodyText().contains("\\\"\\\\\\u0000\\u0009\\ud83c\\udf31"));
    }
}
