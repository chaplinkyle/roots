package dev.roots;

import dev.roots.securityapp.FixtureFailure;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SecurityHeadersIntegrationTest {
    private static final String POLICY = "default-src 'none'; style-src 'self'; frame-ancestors 'none'";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Test
    void configuredPolicyCoversEveryHtmlResponseBoundary() throws Exception {
        try (var application = Roots.start(RootsConfig.forApplication(dev.roots.securityapp.Application.class)
                .port(0)
                .development(false)
                .contentSecurityPolicy(POLICY)
                .mapException(FixtureFailure.class,
                        (request, failure) -> Response.html(503, "<h1>Mapped failure</h1>"))
                .build())) {
            assertPolicy(application.uri(), "/", 200);
            assertPolicy(application.uri(), "/missing", 404);
            assertPolicy(application.uri(), "/api/html", 200);
            assertPolicy(application.uri(), "/api/failure", 503);
            var staticHtml = assertPolicy(application.uri(), "/security.html", 200);
            assertTrue(staticHtml.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
            assertTrue(staticHtml.headers().firstValue("Set-Cookie").isEmpty());

            var head = CLIENT.send(
                    HttpRequest.newBuilder(application.uri()).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, head.statusCode());
            assertEquals("", head.body());
            assertEquals(POLICY, head.headers().firstValue("Content-Security-Policy").orElseThrow());

            var json = get(application.uri(), "/api/json");
            assertEquals(200, json.statusCode());
            assertTrue(json.headers().firstValue("Content-Security-Policy").isEmpty());
        }
    }

    private static HttpResponse<String> assertPolicy(URI base, String path, int status) throws Exception {
        var response = get(base, path);
        assertEquals(status, response.statusCode(), path);
        assertEquals(POLICY, response.headers().firstValue("Content-Security-Policy").orElseThrow(), path);
        return response;
    }

    private static HttpResponse<String> get(URI base, String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }
}
