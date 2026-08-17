package dev.roots;

import org.junit.jupiter.api.AfterEach;
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

final class ErrorPageIntegrationTest {
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");
    private static final Pattern ACTION = Pattern.compile("data-roots-on-click=\"([^\"]+)\"");
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @AfterEach
    void resetFixture() {
        dev.roots.errorpageapp.pages.ErrorPage.FAIL_RENDER = false;
    }

    @Test
    void rendersUnhandledProductionPageFailuresAsSafeLiveErrorPages() throws Exception {
        try (var application = start(false)) {
            var failed = get(application, "/broken", "text/html");
            assertEquals(500, failed.statusCode());
            assertTrue(failed.body().contains("<title>Custom application error</title>"), failed.body());
            assertTrue(failed.body().contains("Something went wrong"), failed.body());
            assertTrue(failed.body().contains("Support trace: "), failed.body());
            assertFalse(failed.body().contains("sensitive broken-page detail"), failed.body());

            var cookie = failed.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            var action = postAction(application, cookie, failed.body());
            assertEquals(200, action.statusCode());
            assertTrue(action.body().contains("Retry attempts: 1"), action.body());

            var head = client.send(HttpRequest.newBuilder(application.uri().resolve("broken"))
                            .header("Accept", "text/html")
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(500, head.statusCode());
            assertEquals("", head.body());
            assertEquals(1, application.runtimeSnapshot().liveViews());

            var api = get(application, "/api/failure", "text/html");
            assertEquals(500, api.statusCode());
            assertFalse(api.body().contains("Something went wrong"), api.body());
        }
    }

    @Test
    void keepsDevelopmentDiagnosticsAndFallsBackWhenTheErrorPageFails() throws Exception {
        try (var development = start(true)) {
            var failed = get(development, "/broken", "text/html");
            assertEquals(500, failed.statusCode());
            assertTrue(failed.body().contains("sensitive broken-page detail"), failed.body());
            assertFalse(failed.body().contains("Something went wrong"), failed.body());
        }

        dev.roots.errorpageapp.pages.ErrorPage.FAIL_RENDER = true;
        try (var production = start(false)) {
            var failed = get(production, "/broken", "text/html");
            assertEquals(500, failed.statusCode());
            assertTrue(failed.body().contains("Roots error"), failed.body());
            assertFalse(failed.body().contains("expected error-page failure"), failed.body());
        }
    }

    @Test
    void preservesConfiguredExceptionMapperPrecedence() throws Exception {
        var config = RootsConfig.forApplication(dev.roots.errorpageapp.Application.class)
                .port(0)
                .development(false)
                .mapException(IllegalStateException.class,
                        (request, failure) -> Response.text(418, "mapped before ErrorPage"))
                .build();
        try (var application = Roots.start(config)) {
            var failed = get(application, "/broken", "text/html");
            assertEquals(418, failed.statusCode());
            assertEquals("mapped before ErrorPage", failed.body());
            assertFalse(failed.body().contains("Something went wrong"));
        }
    }

    private static RunningApplication start(boolean development) {
        return Roots.start(RootsConfig.forApplication(dev.roots.errorpageapp.Application.class)
                .port(0)
                .development(development)
                .build());
    }

    private HttpResponse<String> get(RunningApplication application, String path, String accept) throws Exception {
        return client.send(HttpRequest.newBuilder(application.uri().resolve(path.substring(1)))
                        .header("Accept", accept)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postAction(
            RunningApplication application,
            String cookie,
            String html
    ) throws Exception {
        var form = "_view=" + encode(attribute(VIEW, html))
                + "&_csrf=" + encode(attribute(CSRF, html))
                + "&_protocol=" + encode(Roots.PROTOCOL_VERSION)
                + "&_action=" + encode(attribute(ACTION, html))
                + "&_event=click";
        return client.send(HttpRequest.newBuilder(application.uri().resolve("_roots/action"))
                        .header("Cookie", cookie)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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
