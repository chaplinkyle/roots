package com.chaplin.roots;

import com.chaplin.roots.internal.DevelopmentEvents;
import com.chaplin.roots.testapp.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DevelopmentProtocolIntegrationTest {
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private RunningApplication application;

    @AfterEach
    void stop() {
        if (application != null) {
            application.close();
        }
    }

    @Test
    void servesSessionFreeReloadStreamAndDevelopmentStyles() throws Exception {
        start(true);
        var applicationName = Application.class.getName();
        var known = DevelopmentEvents.current(applicationName).version();
        var response = CLIENT.sendAsync(
                HttpRequest.newBuilder(uri("/_roots/development?since=" + known)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()
        ).get(3, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(200, response.statusCode());
        assertEquals("text/event-stream; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(0, application.runtimeSnapshot().sessions());
        var published = DevelopmentEvents.reload(applicationName);
        var body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("event: reload"), body);
        assertTrue(body.contains("\"version\":" + published.version()), body);

        var styles = get("/_roots/development.css");
        assertEquals(200, styles.statusCode());
        assertEquals("no-store", styles.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(styles.body().contains(".roots-development-error"));
        assertTrue(styles.headers().firstValue("Set-Cookie").isEmpty());
    }

    @Test
    void streamsTextOnlyCompilerDiagnosticAndEmbedsCurrentGeneration() throws Exception {
        start(true);
        var applicationName = Application.class.getName();
        var failure = DevelopmentEvents.failure(applicationName, "bad <script>alert('no')</script>\nline two");
        var response = CLIENT.send(
                HttpRequest.newBuilder(uri("/_roots/development?since=" + failure.version())).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        var event = firstEvent(response);
        response.body().close();

        assertTrue(event.contains("event: compile-error"), event);
        assertTrue(event.contains("\\u003cscript\\u003ealert('no')\\u003c/script\\u003e\\nline two"), event);
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());

        var page = get("/");
        assertTrue(page.body().contains("data-roots-development-generation=\"" + failure.version() + "\""), page.body());
        assertTrue(page.body().contains("href=\"/_roots/development.css\""), page.body());
    }

    @Test
    void rejectsMalformedOrFutureVersionsWithoutSessions() throws Exception {
        start(true);
        var current = DevelopmentEvents.current(Application.class.getName()).version();
        for (var path : new String[]{
                "/_roots/development", "/_roots/development?since=-1",
                "/_roots/development?since=abc", "/_roots/development?since=" + (current + 1)
        }) {
            var response = get(path);
            assertEquals(400, response.statusCode(), path);
            assertTrue(response.headers().firstValue("Set-Cookie").isEmpty(), path);
        }
        var post = CLIENT.send(HttpRequest.newBuilder(uri("/_roots/development?since=" + current))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
        assertEquals("GET", post.headers().firstValue("Allow").orElseThrow());
        assertEquals(0, application.runtimeSnapshot().sessions());
    }

    @Test
    void developmentProtocolIsAbsentInProduction() throws Exception {
        start(false);
        var response = get("/_roots/development?since=0");
        assertEquals(404, response.statusCode());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
        assertFalse(response.body().contains("compile-error"));
        assertEquals(404, get("/_roots/development.css").statusCode());
        assertEquals(404, get("/_roots/inspect?view=no&csrf=no&protocol=" + Roots.PROTOCOL_VERSION).statusCode());
    }

    @Test
    void inspectsOnlyTheAuthenticatedLiveViewInDevelopment() throws Exception {
        start(true);
        var missingSession = get("/_roots/inspect?view=no&csrf=no&protocol=" + Roots.PROTOCOL_VERSION);
        assertEquals(404, missingSession.statusCode());
        assertTrue(missingSession.headers().firstValue("Set-Cookie").isEmpty());
        var page = get("/");
        var view = match(VIEW, page.body());
        var csrf = match(CSRF, page.body());
        var cookie = page.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        var inspection = CLIENT.send(HttpRequest.newBuilder(uri(
                        "/_roots/inspect?view=" + view + "&csrf=" + csrf + "&protocol=" + Roots.PROTOCOL_VERSION
                )).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, inspection.statusCode());
        assertEquals(Roots.PROTOCOL_VERSION,
                inspection.headers().firstValue("X-Roots-Protocol").orElseThrow());
        assertTrue(inspection.body().contains("\"protocol\":\"1\""), inspection.body());
        assertEquals("no-store", inspection.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(inspection.body().contains("\"path\":\"/\""), inspection.body());
        assertTrue(inspection.body().contains("com.chaplin.roots.testapp.pages.Page"), inspection.body());
        assertTrue(inspection.body().contains("com.chaplin.roots.testapp.pages.Layout"), inspection.body());
        assertTrue(inspection.body().contains("\"components\":[]"), inspection.body());
        assertTrue(inspection.body().contains("\"method\":\"increment\""), inspection.body());

        var incompatible = CLIENT.send(HttpRequest.newBuilder(uri(
                        "/_roots/inspect?view=" + view + "&csrf=" + csrf + "&protocol=999"
                )).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, incompatible.statusCode());
        assertEquals(Roots.PROTOCOL_VERSION,
                incompatible.headers().firstValue("X-Roots-Protocol").orElseThrow());
        assertTrue(incompatible.body().contains("protocol version mismatch"), incompatible.body());

        var rejected = CLIENT.send(HttpRequest.newBuilder(uri(
                        "/_roots/inspect?view=" + view + "&csrf=wrong&protocol=" + Roots.PROTOCOL_VERSION
                )).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, rejected.statusCode());
        assertTrue(rejected.headers().firstValue("Set-Cookie").isEmpty());
    }

    private void start(boolean development) {
        application = Roots.start(RootsConfig.forApplication(Application.class)
                .port(0)
                .development(development)
                .instanceFactory(type -> type.getDeclaredConstructor(String.class).newInstance("development fixture"))
                .build());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return application.uri().resolve(path);
    }

    private static String firstEvent(HttpResponse<java.io.InputStream> response) throws Exception {
        var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        while (true) {
            var body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                body.append(line).append('\n');
            }
            if (body.indexOf("event: ") >= 0 || line == null) {
                return body.toString();
            }
        }
    }

    private static String match(Pattern pattern, String body) {
        var matcher = pattern.matcher(body);
        assertTrue(matcher.find(), body);
        return matcher.group(1);
    }
}
