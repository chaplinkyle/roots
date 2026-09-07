package com.acme;

import com.chaplin.roots.Roots;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApplicationIntegrationTest {
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");
    private static final Pattern APPROVE = Pattern.compile("data-roots-on-click=\"([^\"]+:approve)\"");
    private static final Pattern OPEN_SUPPORT = Pattern.compile("data-roots-on-click=\"([^\"]+:open)\"");
    private static final Pattern CREATE = Pattern.compile("data-roots-on-submit=\"([^\"]+:create)\"");
    private static final Pattern SEARCH = Pattern.compile("data-roots-on-input=\"([^\"]+:search)\"");
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static com.chaplin.roots.RunningApplication application;

    @BeforeAll
    static void start() {
        application = Roots.start(Application.config("--port=0", "--production"));
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

        var home = get("/");
        assertTrue(home.body().contains("name=\"theme-color\" data-roots-head=\"theme-color\" content=\"#10241f\""), home.body());
        assertTrue(home.body().contains("property=\"og:site_name\" data-roots-head=\"og:site_name\" content=\"Roots Control\""), home.body());

        var api = get("/api/health");
        assertEquals(200, api.statusCode());
        assertTrue(api.body().contains("\"framework\":\"roots\""));

        var css = get("/app.css");
        assertEquals(200, css.statusCode());
        assertTrue(css.headers().firstValue("content-type").orElseThrow().startsWith("text/css"));
    }

    @Test
    void packagesTheCompileTimeRouteManifest() throws Exception {
        var resource = Objects.requireNonNull(Application.class.getClassLoader().getResource(
                "META-INF/roots/routes/com.acme.Application.routes"));
        try (var stream = resource.openStream()) {
            var manifest = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(manifest.contains(
                    "PAGE\t/customers/{customerId}\tcom.acme.pages.customers.$customerId.Page"));
        }
    }

    @Test
    void cachesServiceDataAndRevalidatesItByTag() throws Exception {
        var first = get("/api/metrics");
        var second = get("/api/metrics");

        assertEquals(200, first.statusCode());
        assertTrue(first.body().contains("\"databaseQuery\":1"), first.body());
        assertEquals(first.body(), second.body());

        var revalidated = CLIENT.send(
                HttpRequest.newBuilder(application.uri().resolve("/api/metrics"))
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, revalidated.statusCode());
        assertEquals("{\"invalidated\":1}", revalidated.body());

        var refreshed = get("/api/metrics");
        assertTrue(refreshed.body().contains("\"databaseQuery\":2"), refreshed.body());
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
        assertTrue(response.body().contains("\"type\":\"VIEW_TRANSITION\""), response.body());
    }

    @Test
    void opensAJavaPortalThroughAComponentAction() throws Exception {
        var initial = get("/");
        var cookie = initial.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        var body = initial.body();
        var form = "_view=" + encode(attribute(VIEW, body))
                + "&_csrf=" + encode(attribute(CSRF, body))
                + "&_action=" + encode(attribute(OPEN_SUPPORT, body))
                + "&_event=click";

        var response = postAction(cookie, form);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("data-roots-portal=\\\"support-overlay\\\""), response.body());
        assertTrue(response.body().contains("The server still owns this overlay."), response.body());
        assertTrue(response.body().contains("\"type\":\"FOCUS\""), response.body());
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

    @Test
    void returnsFieldValidationForAnInvalidEnterpriseForm() throws Exception {
        var initial = get("/customers");
        var cookie = initial.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        var body = initial.body();
        var form = "_view=" + encode(attribute(VIEW, body))
                + "&_csrf=" + encode(attribute(CSRF, body))
                + "&_action=" + encode(attribute(CREATE, body))
                + "&_event=submit&company=&owner=";

        var response = postAction(cookie, form);

        assertEquals(422, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
        assertTrue(response.body().contains("\"error\":\"Check the highlighted customer details.\""),
                response.body());
        assertTrue(response.body().contains("\"company\":[\"Enter a company name.\"]"), response.body());
        assertTrue(response.body().contains("\"owner\":[\"Enter an account owner.\"]"), response.body());
    }

    @Test
    void filtersCustomersThroughADelegatedInputEvent() throws Exception {
        var initial = get("/customers");
        var cookie = initial.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        var body = initial.body();
        var form = "_view=" + encode(attribute(VIEW, body))
                + "&_csrf=" + encode(attribute(CSRF, body))
                + "&_action=" + encode(attribute(SEARCH, body))
                + "&_event=input&query=" + encode("Kestrel");

        var response = postAction(cookie, form);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Kestrel Health"), response.body());
        assertFalse(response.body().contains("Northstar Freight"), response.body());
    }

    @Test
    void submitsAFileDirectlyToAJavaServerAction() throws Exception {
        var initial = get("/customers");
        var cookie = initial.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        var html = initial.body();
        var boundary = "RootsEnterpriseUpload";
        var body = multipart(boundary,
                field("_view", attribute(VIEW, html)),
                field("_csrf", attribute(CSRF, html)),
                field("_protocol", Roots.PROTOCOL_VERSION),
                field("_action", attribute(CREATE, html)),
                field("_event", "submit"),
                field("company", "Juniper Labs"),
                field("owner", "Ari Stone"),
                file("attachment", "evidence.txt", "text/plain", "audit-ready".getBytes(StandardCharsets.UTF_8)));

        var response = CLIENT.send(
                HttpRequest.newBuilder(application.uri().resolve("/_roots/action"))
                        .header("Cookie", cookie)
                        .header("Accept", "application/json")
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Juniper Labs"), response.body());
        assertTrue(response.body().contains("Received evidence.txt · 11 bytes"), response.body());
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
        form += "&_protocol=" + encode(Roots.PROTOCOL_VERSION);
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

    private static byte[] multipart(String boundary, MultipartPart... parts) {
        var output = new ByteArrayOutputStream();
        for (var part : parts) {
            output.writeBytes(("--" + boundary + "\r\n" + part.headers() + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.writeBytes(part.content());
            output.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    private static MultipartPart field(String name, String value) {
        return new MultipartPart(
                "Content-Disposition: form-data; name=\"" + name + "\"",
                value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static MultipartPart file(String name, String filename, String contentType, byte[] content) {
        return new MultipartPart(
                "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                        + "Content-Type: " + contentType,
                content
        );
    }

    private record MultipartPart(String headers, byte[] content) {
    }
}
