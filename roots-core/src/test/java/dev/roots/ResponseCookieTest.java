package dev.roots;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResponseCookieTest {
    @Test
    void buildsConservativeDefaultAndAppendsRepeatedHeaders() {
        var cookie = ResponseCookie.builder("theme", "dark").build();

        assertEquals("theme", cookie.name());
        assertEquals("dark", cookie.value());
        assertEquals("/", cookie.path());
        assertTrue(cookie.domain().isEmpty());
        assertTrue(cookie.maxAgeSeconds().isEmpty());
        assertTrue(cookie.expires().isEmpty());
        assertFalse(cookie.secure());
        assertTrue(cookie.httpOnly());
        assertEquals(ResponseCookie.SameSite.LAX, cookie.sameSite());
        assertFalse(cookie.partitioned());
        assertEquals("theme=dark; Path=/; HttpOnly; SameSite=Lax", cookie.headerValue());

        var response = Response.text(200, "ok")
                .withAddedHeader("X-Value", "one")
                .withAddedHeader("x-value", "two")
                .withCookie(cookie)
                .withCookie(ResponseCookie.builder("locale", "en-US").httpOnly(false).build());
        assertEquals(List.of("one", "two"), response.headers().get("x-value"));
        assertEquals(List.of(
                "theme=dark; Path=/; HttpOnly; SameSite=Lax",
                "locale=en-US; Path=/; SameSite=Lax"
        ), response.headers().get("Set-Cookie"));
        assertThrows(NullPointerException.class, () -> response.withCookie(null));
        assertThrows(IllegalArgumentException.class, () -> response.withAddedHeader("Bad Name", "value"));
        assertThrows(IllegalArgumentException.class, () -> response.withAddedHeader("X-Test", "bad\r\nvalue"));
    }

    @Test
    void serializesFullSecureCookieAndExpiration() {
        var expires = Instant.parse("2030-01-02T03:04:05Z");
        var cookie = ResponseCookie.builder("__Host-Http-session", "abc_123")
                .maxAge(Duration.ofHours(1))
                .expires(expires)
                .secure(true)
                .sameSite(ResponseCookie.SameSite.NONE)
                .partitioned(true)
                .build();

        assertEquals(3_600, cookie.maxAgeSeconds().orElseThrow());
        assertEquals(expires, cookie.expires().orElseThrow());
        assertEquals(
                "__Host-Http-session=abc_123; Path=/; Max-Age=3600; "
                        + "Expires=Wed, 02 Jan 2030 03:04:05 GMT; Secure; HttpOnly; SameSite=None; Partitioned",
                cookie.headerValue()
        );

        var scoped = ResponseCookie.builder("preference", "compact")
                .path("/company/roots")
                .domain("APP.EXAMPLE.COM")
                .maxAgeSeconds(60)
                .secure(true)
                .httpOnly(false)
                .sameSite(ResponseCookie.SameSite.STRICT)
                .build();
        assertEquals("app.example.com", scoped.domain().orElseThrow());
        assertEquals("preference=compact; Path=/company/roots; Domain=app.example.com; Max-Age=60; "
                        + "Secure; SameSite=Strict", scoped.headerValue());

        assertEquals("gone=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax",
                ResponseCookie.expire("gone").build().headerValue());
    }

    @Test
    void rejectsAmbiguousOrInsecureCookieConfiguration() {
        assertThrows(NullPointerException.class, () -> ResponseCookie.builder(null, "value"));
        assertThrows(NullPointerException.class, () -> ResponseCookie.builder("name", null));
        assertThrows(IllegalArgumentException.class, () -> ResponseCookie.builder("", "value"));
        assertThrows(IllegalArgumentException.class, () -> ResponseCookie.builder("bad name", "value"));
        assertThrows(IllegalArgumentException.class, () -> ResponseCookie.builder("x".repeat(257), "value"));
        assertThrows(IllegalArgumentException.class, () -> ResponseCookie.builder("name", "bad;value"));
        assertThrows(IllegalArgumentException.class, () -> ResponseCookie.builder("name", "snowman-☃"));
        assertThrows(IllegalArgumentException.class, () -> ResponseCookie.builder("name", "x".repeat(4_097)));

        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("name", "value").path("relative"));
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("name", "value").path("/bad; Secure"));
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("name", "value").domain(".example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("name", "value").domain("bad..example"));
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("name", "value").domain("x".repeat(64) + ".example"));
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("name", "value").maxAgeSeconds(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("name", "value").maxAge(Duration.ofMillis(1)));
        assertThrows(NullPointerException.class,
                () -> ResponseCookie.builder("name", "value").maxAge(null));
        assertThrows(NullPointerException.class,
                () -> ResponseCookie.builder("name", "value").sameSite(null));
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("name", "value")
                        .sameSite(ResponseCookie.SameSite.NONE).build());
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("name", "value").partitioned(true).build());
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("__Secure-name", "value").build());
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("__Http-name", "value").secure(true).httpOnly(false).build());
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("__Host-name", "value").secure(true).path("/app").build());
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("__Host-name", "value").secure(true).domain("example.com").build());
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("name", "value").expires(Instant.parse("1500-01-01T00:00:00Z")));
        assertThrows(IllegalArgumentException.class,
                () -> ResponseCookie.builder("name", "value").expires(Instant.MAX));
    }

    @Test
    void requestParsingIsTolerantBoundedAndFirstValueWins() {
        var request = request(Map.of(
                "cookie", List.of(
                        "theme=dark; quoted=\"two\"; duplicate=first; empty=; malformed; bad value=no",
                        "duplicate=second; locale=en-US; unicode=☃"
                ),
                "X-Test", List.of("yes")
        ));

        assertEquals(Map.of(
                "theme", "dark",
                "quoted", "two",
                "duplicate", "first",
                "empty", "",
                "locale", "en-US"
        ), request.cookies());
        assertEquals("dark", request.cookie("theme").orElseThrow());
        assertTrue(request.cookie("missing").isEmpty());
        assertEquals(request.cookies(), request.withLogicalPath("/next").cookies());
        assertThrows(UnsupportedOperationException.class, () -> request.cookies().put("no", "value"));
        assertThrows(NullPointerException.class, () -> request.cookie(null));
        assertThrows(IllegalArgumentException.class, () -> request.cookie("bad name"));

        var many = new StringBuilder();
        for (var index = 0; index < 130; index++) {
            if (index > 0) many.append("; ");
            many.append("cookie-").append(index).append("=value");
        }
        assertEquals(128, request(Map.of("Cookie", List.of(many.toString()))).cookies().size());
        assertTrue(request(Map.of("Cookie", List.of("ignored=" + "x".repeat(16_384)))).cookies().isEmpty());
    }

    private static Request request(Map<String, List<String>> headers) {
        return new Request(
                "GET", "/", "/", Map.of(), Map.of(), headers, Map.of(), Map.of(), new byte[0],
                new Session("cookie-test"), RootsCache.disabled(), java.util.Optional.empty(),
                TraceContext.create(), "", ClientConnection.unknown("http", "localhost")
        );
    }
}
