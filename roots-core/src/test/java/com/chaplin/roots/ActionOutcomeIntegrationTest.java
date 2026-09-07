package com.chaplin.roots;

import com.chaplin.roots.actionfailureapp.Application;
import com.chaplin.roots.actionfailureapp.pages.Page;
import java.net.http.*;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ActionOutcomeIntegrationTest {
    @Test
    void neverExecutesAnotherActionOnAViewAfterUnexpectedHandlerOrRenderingFailure() throws Exception {
        try (var app = Roots.start(config().build()); var client = HttpClient.newHttpClient()) {
            for (var mode : List.of("handler", "render", "render-validation", "error", "interrupt")) {
                var view = open(app, client, mode);
                int before = Page.WRITES.get();
                try {
                    var first = action(app, client, view, "");
                    assertEquals(mode.equals("render-validation") ? 422 : 500, first.statusCode(), mode);
                    assertEquals("uncertain", first.headers().firstValue("X-Roots-Action-Outcome").orElseThrow());
                } catch (java.io.IOException transportInterrupted) {
                    assertEquals("interrupt", mode, "Only an interrupted request may lose the response");
                }
                for (int attempt = 0; attempt < 3; attempt++) {
                    var retry = action(app, client, view, "");
                    assertEquals(409, retry.statusCode(), mode);
                    assertEquals("uncertain", retry.headers().firstValue("X-Roots-Action-Outcome").orElseThrow());
                }
                assertEquals(before + 1, Page.WRITES.get(), mode);
            }
            assertEquals(200, action(app, client, open(app, client, "ok"), "").statusCode());
        }
    }

    @Test
    void preservesTheOriginalExceptionMapperWithoutLettingItHideExecutionUncertainty() throws Exception {
        try (var app = Roots.start(config().mapException(IllegalStateException.class,
                (request, failure) -> Response.json(422, "{\"error\":\"mapped business failure\"}")).build());
             var client = HttpClient.newHttpClient()) {
            var view = open(app, client, "handler");
            var response = action(app, client, view, "");
            assertEquals(422, response.statusCode());
            assertTrue(response.body().contains("mapped business failure"));
            assertEquals("uncertain", response.headers().firstValue("X-Roots-Action-Outcome").orElseThrow());
            assertEquals(409, action(app, client, view, "").statusCode());
        }
    }

    @Test
    void validationBeforeBusinessWritesAllowsCorrectionOnTheSameView() throws Exception {
        try (var app = Roots.start(config().build()); var client = HttpClient.newHttpClient()) {
            var view = open(app, client, "validate");
            int before = Page.WRITES.get();
            var rejected = action(app, client, view, "");
            assertEquals(422, rejected.statusCode());
            assertTrue(rejected.headers().firstValue("X-Roots-Action-Outcome").isEmpty());
            assertEquals(before, Page.WRITES.get());
            assertEquals(200, action(app, client, view, "&value=corrected").statusCode());
            assertEquals(before + 1, Page.WRITES.get());
        }
    }

    private static RootsConfig.Builder config() {
        return RootsConfig.forApplication(Application.class).port(0).development(false);
    }

    private static BrowserView open(RunningApplication app, HttpClient client, String mode) throws Exception {
        var response = client.send(HttpRequest.newBuilder(app.uri().resolve("/?mode=" + mode)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var body = "_view=" + attribute("data-roots-view", response.body())
                + "&_csrf=" + attribute("data-roots-csrf", response.body())
                + "&_action=" + attribute("data-roots-on-click", response.body())
                + "&_event=click&_protocol=" + Roots.PROTOCOL_VERSION;
        return new BrowserView(response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0], body);
    }

    private static HttpResponse<String> action(RunningApplication app, HttpClient client, BrowserView view,
            String extra) throws Exception {
        return client.send(HttpRequest.newBuilder(app.uri().resolve("/_roots/action"))
                .header("Cookie", view.cookie()).header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(view.body() + extra)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String attribute(String name, String html) {
        var matcher = Pattern.compile(name + "=\"([^\"]+)\"").matcher(html);
        assertTrue(matcher.find(), name);
        return matcher.group(1);
    }

    private record BrowserView(String cookie, String body) { }
}
