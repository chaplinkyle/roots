package com.chaplin.roots.examples.chat;

import com.chaplin.roots.Roots;
import com.chaplin.roots.RootsConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApplicationIntegrationTest {
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");
    private static final Pattern SEND = Pattern.compile("data-roots-on-submit=\"([^\"]+:send)\"");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static com.chaplin.roots.RunningApplication application;

    @BeforeAll
    static void start() {
        application = Roots.start(RootsConfig.forApplication(Application.class)
                .port(0)
                .development(false)
                .build());
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @Test
    void servesTheChatAndItsStylesheet() throws Exception {
        var page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Coordinate without leaving Java."));
        assertTrue(page.body().contains("Maya Chen"));
        assertTrue(page.body().contains("data-roots-on-submit"));
        assertTrue(page.body().contains("class=\"room-rail\" tabindex=\"0\""));
        assertTrue(page.body().contains("class=\"message-ledger\" role=\"log\" tabindex=\"0\""));

        var stylesheet = get("/chat.css");
        assertEquals(200, stylesheet.statusCode());
        assertTrue(stylesheet.headers().firstValue("content-type").orElseThrow().startsWith("text/css"));
        assertTrue(stylesheet.body().contains(".message-ledger"));
    }

    @Test
    void packagesTheCompileTimeRouteManifest() throws Exception {
        var resource = Objects.requireNonNull(Application.class.getClassLoader().getResource(
                "META-INF/roots/routes/com.chaplin.roots.examples.chat.Application.routes"));
        try (var stream = resource.openStream()) {
            var manifest = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(manifest.contains("PAGE\t/\tcom.chaplin.roots.examples.chat.pages.Page"));
        }
    }

    @Test
    void postsAnEscapedMessageThroughAnAnnotatedJavaAction() throws Exception {
        var initial = view();
        var message = "Status <green> & checked " + System.nanoTime();

        var response = post(initial, "Integration Bot", message);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Integration Bot"), response.body());
        assertTrue(response.body().contains("Status \\u0026lt;green\\u0026gt; \\u0026amp; checked"), response.body());
        assertTrue(response.body().contains("\"revision\":2"), response.body());
        assertTrue(response.body().contains("SCROLL_INTO_VIEW"), response.body());
        assertTrue(response.body().contains("FOCUS"), response.body());
    }

    @Test
    void pushesARevisionedPatchToAnotherBrowserView() throws Exception {
        var receiver = view();
        var sender = view();
        var streamRequest = HttpRequest.newBuilder(application.uri().resolve(
                        "/_roots/stream?view=" + encode(receiver.viewId()) + "&csrf=" + encode(receiver.csrf())
                                + "&protocol=" + encode(Roots.PROTOCOL_VERSION)))
                .header("Cookie", receiver.cookie())
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        var stream = CLIENT.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());

        try (var body = stream.body();
             var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            var initial = readNextPatch(reader).get(5, TimeUnit.SECONDS);
            assertTrue(initial.contains("\"revision\":1"), initial);
            var message = "Cross-view dispatch " + System.nanoTime();

            var posted = post(sender, "Relay Test", message);
            assertEquals(200, posted.statusCode());

            var event = readNextPatch(reader).get(5, TimeUnit.SECONDS);
            assertTrue(event.contains(message), event);
            assertTrue(event.contains("event: patch"), event);
            assertTrue(event.contains("\"revision\":2"), event);
        }
    }

    private static CompletableFuture<String> readNextPatch(BufferedReader reader) {
        var result = new CompletableFuture<String>();
        Thread.startVirtualThread(() -> {
            try {
                var event = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (event.toString().contains("event: patch")) {
                            result.complete(event.toString());
                            return;
                        }
                        event.setLength(0);
                    } else {
                        event.append(line).append('\n');
                    }
                }
                result.completeExceptionally(new IllegalStateException("SSE stream closed before a patch arrived"));
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    private static BrowserView view() throws Exception {
        var response = get("/");
        var cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        var body = response.body();
        return new BrowserView(
                cookie,
                attribute(VIEW, body),
                attribute(CSRF, body),
                attribute(SEND, body)
        );
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(application.uri().resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static HttpResponse<String> post(BrowserView view, String author, String message) throws Exception {
        var form = "_view=" + encode(view.viewId())
                + "&_csrf=" + encode(view.csrf())
                + "&_protocol=" + encode(Roots.PROTOCOL_VERSION)
                + "&_action=" + encode(view.sendAction())
                + "&_event=submit"
                + "&author=" + encode(author)
                + "&message=" + encode(message);
        return CLIENT.send(
                HttpRequest.newBuilder(application.uri().resolve("/_roots/action"))
                        .header("Cookie", view.cookie())
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

    private record BrowserView(String cookie, String viewId, String csrf, String sendAction) {
    }
}
