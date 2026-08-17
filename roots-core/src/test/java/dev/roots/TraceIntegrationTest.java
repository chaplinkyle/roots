package dev.roots;

import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TraceIntegrationTest {
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");
    private static final Pattern ACTION = Pattern.compile("data-roots-on-click=\"([^\"]+:trace)\"");

    @Test
    void propagatesTraceContextThroughPagesActionsResponsesAndObservations() throws Exception {
        var observations = new CopyOnWriteArrayList<RequestObservation>();
        var middlewareTrace = new AtomicReference<TraceContext>();
        var config = RootsConfig.forApplication(dev.roots.traceapp.Application.class)
                .port(0)
                .development(false)
                .observeRequests(observations::add)
                .use((request, chain) -> {
                    middlewareTrace.set(request.traceContext());
                    return chain.next();
                })
                .build();
        try (var application = Roots.start(config);
             var client = HttpClient.newBuilder().cookieHandler(new CookieManager())
                     .connectTimeout(Duration.ofSeconds(3)).build()) {
            var initial = client.send(HttpRequest.newBuilder(application.uri())
                    .header("traceparent", "00-" + TRACE + "-00f067aa0ba902b7-03")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            var initialTraceparent = initial.headers().firstValue("traceparent").orElseThrow();
            var initialSpan = field(initialTraceparent, 2);

            assertEquals(200, initial.statusCode(), initial.body());
            assertEquals(TRACE, field(initialTraceparent, 1));
            assertNotEquals("00f067aa0ba902b7", initialSpan);
            assertTrue(initial.body().contains("render-span=" + initialSpan), initial.body());
            assertEquals(initialSpan, middlewareTrace.get().spanId());

            var actionBody = "_view=" + encode(match(VIEW, initial.body()))
                    + "&_csrf=" + encode(match(CSRF, initial.body()))
                    + "&_protocol=" + encode(Roots.PROTOCOL_VERSION)
                    + "&_action=" + encode(match(ACTION, initial.body()))
                    + "&_event=click";
            var action = client.send(HttpRequest.newBuilder(application.uri().resolve("/_roots/action"))
                    .header("traceparent", "00-" + TRACE + "-1111111111111111-01")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(actionBody)).build(),
                    HttpResponse.BodyHandlers.ofString());
            var actionTraceparent = action.headers().firstValue("traceparent").orElseThrow();
            var actionSpan = field(actionTraceparent, 2);

            assertEquals(200, action.statusCode(), action.body());
            assertEquals(TRACE, field(actionTraceparent, 1));
            assertTrue(action.body().contains("render-span=" + actionSpan), action.body());
            assertTrue(action.body().contains("action-span=" + actionSpan), action.body());
            assertEquals(actionSpan, middlewareTrace.get().spanId());

            var invalid = client.send(HttpRequest.newBuilder(application.uri().resolve("/_roots/health"))
                    .header("traceparent", "not-a-trace")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, invalid.statusCode());
            assertNotEquals(TRACE, field(invalid.headers().firstValue("traceparent").orElseThrow(), 1));

            assertEquals(3, observations.size());
            var actionObservation = observations.stream()
                    .filter(observation -> observation.traceContext().spanId().equals(actionSpan))
                    .findFirst().orElseThrow();
            assertEquals("/", actionObservation.path());
            assertEquals("/_roots/action", actionObservation.transportPath());
            assertEquals(200, actionObservation.status());
            assertEquals(RequestOutcome.SUCCESS, actionObservation.outcome());
            assertEquals(action.body().getBytes(StandardCharsets.UTF_8).length,
                    actionObservation.responseBytes());
            assertFalse(actionObservation.json().contains("_csrf"));
        }
    }

    private static String field(String traceparent, int index) {
        return traceparent.split("-", -1)[index];
    }

    private static String match(Pattern pattern, String body) {
        var matcher = pattern.matcher(body);
        assertTrue(matcher.find(), body);
        return matcher.group(1);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
