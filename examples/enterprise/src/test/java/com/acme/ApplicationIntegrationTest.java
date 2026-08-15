package com.acme;

import dev.roots.Roots;
import dev.roots.RootsConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApplicationIntegrationTest {
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");
    private static final Pattern APPROVE = Pattern.compile("data-roots-on-click=\"([^\"]+:approve)\"");
    private static final Pattern CREATE = Pattern.compile("data-roots-on-submit=\"([^\"]+:create)\"");
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static dev.roots.RunningApplication application;

    @BeforeAll
    static void start() {
        application = Roots.start(RootsConfig.forApplication(Application.class).port(0).development(false).build());
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @Test
    void discoversConventionDynamicAndAnnotatedRoutes() throws Exception {
        assertPage("/", "The JVM is the full stack.");
        assertPage("/customers", "Account directory");
        assertPage("/customers/1042", "Customer 1042");
        assertPage("/activity", "Annotation route");

        var api = get("/api/health");
        assertEquals(200, api.statusCode());
        assertTrue(api.body().contains("\"framework\":\"roots\""));

        var css = get("/app.css");
        assertEquals(200, css.statusCode());
        assertTrue(css.headers().firstValue("content-type").orElseThrow().startsWith("text/css"));
    }

    @Test
    void invokesAnAnnotatedComponentActionAndReturnsARevisionedPatch() throws Exception {
        var initial = get("/");
        var cookie = initial.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        var body = initial.body();
        var form = "_view=" + encode(attribute(VIEW, body))
                + "&_csrf=" + encode(attribute(CSRF, body))
                + "&_action=" + encode(attribute(APPROVE, body))
                + "&_event=click";

        var response = postAction(cookie, form);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("5 access reviews still need a decision."), response.body());
        assertTrue(response.body().contains("\"revision\":2"));
    }

    @Test
    void submitsAJavaFormActionWithoutAnApplicationApi() throws Exception {
        var initial = get("/customers");
        var cookie = initial.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        var body = initial.body();
        var form = "_view=" + encode(attribute(VIEW, body))
                + "&_csrf=" + encode(attribute(CSRF, body))
                + "&_action=" + encode(attribute(CREATE, body))
                + "&_event=submit&company=" + encode("Atlas Electric")
                + "&owner=" + encode("Rae Kim");

        var response = postAction(cookie, form);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Atlas Electric"));
        assertTrue(response.body().contains("Rae Kim"));
    }

    private static void assertPage(String path, String text) throws Exception {
        var response = get(path);
        assertEquals(200, response.statusCode(), path);
        assertTrue(response.body().toLowerCase().contains(text.toLowerCase()), path);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(application.uri().resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static HttpResponse<String> postAction(String cookie, String form) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(application.uri().resolve("/_roots/action"))
                        .header("Cookie", cookie)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static String attribute(Pattern pattern, String html) {
        var matcher = pattern.matcher(html);
        assertTrue(matcher.find(), pattern.pattern());
        return matcher.group(1);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
