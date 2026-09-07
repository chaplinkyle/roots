package com.chaplin.roots;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NotFoundPageIntegrationTest {
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");
    private static final Pattern ACTION = Pattern.compile("data-roots-on-click=\"([^\"]+)\"");
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Test
    void rendersRootNotFoundConventionAsAStatusPreservingLivePage() throws Exception {
        try (var application = Roots.start(RootsConfig
                .forApplication(com.chaplin.roots.notfoundapp.Application.class)
                .port(0)
                .development(false)
                .build())) {
            var missing = send(HttpRequest.newBuilder(
                            application.uri().resolve("missing%3Ccustomer%3E"))
                    .header("Accept", "text/html")
                    .GET()
                    .build());

            assertEquals(404, missing.statusCode());
            assertTrue(missing.body().contains("<title>Custom not found</title>"), missing.body());
            assertTrue(missing.body().contains("Custom 404"), missing.body());
            assertTrue(missing.body().contains("Missing path: /missing&lt;customer&gt;"), missing.body());
            assertTrue(missing.body().contains("data-not-found-layout=\"true\""), missing.body());
            assertTrue(missing.body().contains("data-roots-view="), missing.body());
            assertTrue(missing.headers().firstValue("Content-Security-Policy").isPresent());

            var cookie = missing.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            var view = attribute(VIEW, missing.body());
            var csrf = attribute(CSRF, missing.body());
            var action = attribute(ACTION, missing.body());
            var actionResponse = send(HttpRequest.newBuilder(
                            application.uri().resolve("_roots/action"))
                    .header("Cookie", cookie)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "_view=" + encode(view)
                                    + "&_csrf=" + encode(csrf)
                                    + "&_protocol=" + encode(Roots.PROTOCOL_VERSION)
                                    + "&_action=" + encode(action)
                                    + "&_event=click"))
                    .build());
            assertEquals(200, actionResponse.statusCode());
            assertTrue(actionResponse.body().contains("Recovery attempts: 1"), actionResponse.body());

            var head = send(HttpRequest.newBuilder(
                            application.uri().resolve("another-missing-page"))
                    .header("Accept", "text/html")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build());
            assertEquals(404, head.statusCode());
            assertEquals("", head.body());
            assertEquals(1, application.runtimeSnapshot().liveViews(),
                    "HEAD must render metadata without retaining another live view");

            var missingAsset = send(HttpRequest.newBuilder(
                            application.uri().resolve("missing.png"))
                    .header("Accept", "image/png")
                    .GET()
                    .build());
            assertEquals(404, missingAsset.statusCode());
            assertFalse(missingAsset.body().contains("Custom 404"), missingAsset.body());
            assertTrue(missingAsset.headers().firstValue("Set-Cookie").isEmpty());

            var missingApi = send(HttpRequest.newBuilder(
                            application.uri().resolve("api/missing"))
                    .header("Accept", "application/json")
                    .GET()
                    .build());
            assertEquals(404, missingApi.statusCode());
            assertFalse(missingApi.body().contains("Custom 404"), missingApi.body());
            assertTrue(missingApi.headers().firstValue("Set-Cookie").isEmpty());
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String attribute(Pattern pattern, String html) {
        var matcher = pattern.matcher(html);
        assertTrue(matcher.find(), () -> "Missing " + pattern.pattern() + " in " + html);
        return matcher.group(1);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
