package com.chaplin.roots;

import com.chaplin.roots.testapp.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.Socket;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RootServerIntegrationTest {
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");
    private static final Pattern INCREMENT = Pattern.compile("data-roots-on-click=\"([^\"]+:increment)\"");
    private static final Pattern EXPLODE = Pattern.compile("data-roots-on-click=\"([^\"]+:explode)\"");
    private static final Pattern VALIDATE = Pattern.compile("data-roots-on-click=\"([^\"]+:validate)\"");
    private static final Pattern REDIRECT = Pattern.compile("data-roots-on-click=\"([^\"]+:redirect)\"");
    private static final Pattern REMOVE_PROBES = Pattern.compile("data-roots-on-click=\"([^\"]+:removeProbes)\"");
    private static final Pattern SCOPED_INCREMENT = Pattern.compile("data-roots-on-click=\"([^\"]+:increment)\"");
    private static final Pattern SCOPED_NOOP = Pattern.compile("data-roots-on-dblclick=\"([^\"]+:noop)\"");
    private static final Pattern COOKIE_SET = Pattern.compile("data-roots-on-click=\"([^\"]+:setCookie)\"");
    private static final Pattern COOKIE_DELETE = Pattern.compile("data-roots-on-click=\"([^\"]+:deleteCookie)\"");
    private static final Pattern COOKIE_FAIL = Pattern.compile("data-roots-on-click=\"([^\"]+:failCookie)\"");
    private static final Pattern REVISION = Pattern.compile("\"revision\":(\\d+)");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final List<String> trace = new CopyOnWriteArrayList<>();
    private final Set<Class<?>> createdTypes = ConcurrentHashMap.newKeySet();
    private RunningApplication application;
    private Path requestBodyDirectory;

    @BeforeEach
    void start() throws Exception {
        com.chaplin.roots.testapp.pages.Page.reset();
        com.chaplin.roots.testapp.pages.Layout.reset();
        com.chaplin.roots.testapp.pages.lifecycle.Page.reset();
        com.chaplin.roots.testapp.components.LifecycleProbe.reset();
        com.chaplin.roots.testapp.api.cache.Route.reset();
        com.chaplin.roots.testapp.api.spool.Route.reset();
        com.chaplin.roots.testapp.api.stream.Route.reset();
        com.chaplin.roots.testapp.pages.cache.Page.reset();
        requestBodyDirectory = Files.createTempDirectory("roots-request-body-test-");
        InstanceFactory factory = type -> {
            createdTypes.add(type);
            try {
                var constructor = type.getDeclaredConstructor(String.class);
                return constructor.newInstance("injected dependency");
            } catch (InvocationTargetException exception) {
                switch (exception.getCause()) {
                    case Exception cause -> throw cause;
                    case Error cause -> throw cause;
                    case Throwable cause -> throw new IllegalStateException(cause);
                }
            }
        };
        application = Roots.start(RootsConfig.forApplication(Application.class)
                .port(0)
                .development(false)
                .secureCookies(true)
                .requestBodyMemoryThreshold(65_536)
                .requestBodyTemporaryDirectory(requestBodyDirectory)
                .instanceFactory(factory)
                .mapException(com.chaplin.roots.testapp.FixtureProblem.class, (request, failure) -> Response.json(
                        418,
                        "{\"error\":\"mapped\",\"logicalPath\":\"" + request.path()
                                + "\",\"transportPath\":\"" + request.transportPath() + "\"}"
                ))
                .use((request, chain) -> {
                    trace.add("outer-before:" + request.path());
                    var response = chain.next();
                    trace.add("outer-after:" + request.path());
                    return response.withHeader("X-Outer-Middleware", "applied");
                })
                .use((request, chain) -> {
                    trace.add("inner-before:" + request.path());
                    try {
                        if (request.path().equals("/secure") && request.header("X-Test-Authorization").isEmpty()) {
                            return Response.text(401, "authorization required");
                        }
                        return chain.next();
                    } catch (IllegalStateException exception) {
                        return Response.json(503, "{\"mapped\":\"" + exception.getMessage() + "\"}");
                    } finally {
                        trace.add("inner-after:" + request.path());
                    }
                })
                .build());
    }

    @Test
    void streamsFixedAndUnknownLengthApiBodiesAndSkipsWritersForHead() throws Exception {
        var fixed = CLIENT.send(HttpRequest.newBuilder(application.uri().resolve("/api/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, fixed.statusCode());
        assertEquals(com.chaplin.roots.testapp.api.stream.Route.LENGTH, fixed.body().length);
        assertTrue(IntStream.range(0, fixed.body().length).allMatch(index -> fixed.body()[index] == 'R'));
        assertEquals(Integer.toString(com.chaplin.roots.testapp.api.stream.Route.LENGTH),
                fixed.headers().firstValue("Content-Length").orElseThrow());
        assertEquals("fixed", fixed.headers().firstValue("X-Roots-Streaming").orElseThrow());
        assertEquals("applied", fixed.headers().firstValue("X-Outer-Middleware").orElseThrow());
        assertEquals(1, com.chaplin.roots.testapp.api.stream.Route.writes());

        var head = CLIENT.send(HttpRequest.newBuilder(application.uri().resolve("/api/stream"))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
        assertEquals(Integer.toString(com.chaplin.roots.testapp.api.stream.Route.LENGTH),
                head.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(1, com.chaplin.roots.testapp.api.stream.Route.writes(), "HEAD must not invoke a lazy body writer");

        var options = CLIENT.send(HttpRequest.newBuilder(application.uri().resolve("/api/stream"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(204, options.statusCode());
        assertEquals(0, options.body().length);
        assertEquals("GET, HEAD, OPTIONS", options.headers().firstValue("Allow").orElseThrow());
        assertEquals("applied", options.headers().firstValue("X-Outer-Middleware").orElseThrow());
        assertEquals(1, com.chaplin.roots.testapp.api.stream.Route.writes());

        var unsupported = CLIENT.send(HttpRequest.newBuilder(application.uri().resolve("/api/stream"))
                        .method("TRACE", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, unsupported.statusCode());
        assertEquals("GET, HEAD, OPTIONS", unsupported.headers().firstValue("Allow").orElseThrow());
        assertEquals(1, com.chaplin.roots.testapp.api.stream.Route.writes());

        var chunked = CLIENT.send(HttpRequest.newBuilder(application.uri().resolve("/api/chunked")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, chunked.statusCode());
        assertEquals("first-second", chunked.body());
        assertTrue(chunked.headers().firstValue("Content-Length").isEmpty());
        assertEquals("chunked", chunked.headers().firstValue("X-Roots-Streaming").orElseThrow());
    }

    @AfterEach
    void stop() throws Exception {
        application.closeGracefully(Duration.ofSeconds(5));
        try (var entries = Files.list(requestBodyDirectory)) {
            assertEquals(0, entries.count(), "request-scoped temporary bodies must be deleted");
        }
        Files.deleteIfExists(requestBodyDirectory);
    }

    @Test
    void createsPagesLayoutsAndDynamicApiRoutesThroughTheFactory() throws Exception {
        var page = get("/");

        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("injected dependency"), page.body());
        assertTrue(page.body().contains("data-layout=\"injected dependency\""), page.body());
        assertTrue(page.body().contains("<link rel=\"stylesheet\" data-roots-style href=\"/base.css\">"), page.body());
        assertEquals("applied", page.headers().firstValue("X-Outer-Middleware").orElseThrow());
        assertEquals(List.of(
                "outer-before:/",
                "inner-before:/",
                "inner-after:/",
                "outer-after:/"
        ), trace);

        trace.clear();
        var api = get("/api/items/42");
        assertEquals(200, api.statusCode());
        assertEquals("{\"item\":\"42\",\"injected\":\"injected dependency\"}", api.body());
        assertTrue(createdTypes.contains(com.chaplin.roots.testapp.pages.Page.class));
        assertTrue(createdTypes.contains(com.chaplin.roots.testapp.pages.Layout.class));
        assertTrue(createdTypes.contains(com.chaplin.roots.testapp.api.items.$itemId.Route.class));
    }

    @Test
    void bindsAndValidatesTypedRecordsInApiRoutes() throws Exception {
        var accepted = postForm("/api/form", null, "email=%20ada%40example.com%20&quantity=2");
        assertEquals(201, accepted.statusCode());
        assertEquals("ada@example.com:2", accepted.body());

        var rejected = postForm("/api/form", null, "email=invalid&quantity=0");
        assertEquals(422, rejected.statusCode());
        assertTrue(rejected.body().contains("\"error\":\"Check the API form.\""), rejected.body());
        assertTrue(rejected.body().contains("\"email\":[\"Enter a valid email address.\"]"), rejected.body());
        assertTrue(rejected.body().contains("\"quantity\":[\"Order at least 1 item.\"]"), rejected.body());
    }

    @Test
    void sharesTaggedCacheAcrossPagesApiRoutesAndRunningApplicationRevalidation() throws Exception {
        var firstApi = get("/api/cache");
        var secondApi = get("/api/cache");
        var firstPage = get("/cache");
        var secondPage = get("/cache");

        assertEquals("injected dependency:1", firstApi.body());
        assertEquals(firstApi.body(), secondApi.body());
        assertTrue(firstPage.body().contains("injected dependency:1"), firstPage.body());
        assertTrue(secondPage.body().contains("injected dependency:1"), secondPage.body());
        assertEquals(2, application.cache().snapshot().entries());

        assertEquals(2, application.cache().invalidateTag("fixture-cache"));

        assertEquals("injected dependency:2", get("/api/cache").body());
        assertTrue(get("/cache").body().contains("injected dependency:2"));
        assertEquals(2, application.cache().snapshot().invalidations());
    }

    @Test
    void middlewareCanGuardPagesAndMapDownstreamExceptions() throws Exception {
        var rejected = get("/secure");
        assertEquals(401, rejected.statusCode());
        assertEquals("authorization required", rejected.body());
        assertFalse(createdTypes.contains(com.chaplin.roots.testapp.pages.secure.Page.class));

        var accepted = send(HttpRequest.newBuilder(uri("/secure"))
                .header("X-Test-Authorization", "allowed")
                .GET()
                .build());
        assertEquals(200, accepted.statusCode());
        assertTrue(accepted.body().contains("Secure injected dependency"));

        var mapped = get("/api/failure");
        assertEquals(503, mapped.statusCode());
        assertTrue(mapped.body().contains("fixture exploded"), mapped.body());
    }

    @Test
    void propagatesRequestCookiesAndCommitsActionCookiesOnlyOnSuccess() throws Exception {
        var initial = send(HttpRequest.newBuilder(uri("/cookies"))
                .header("Cookie", "theme=blue; malformed; bad value=no")
                .GET()
                .build());
        var view = liveView(initial);
        assertTrue(initial.body().contains("render=blue; observed=none"), initial.body());

        var set = postForm(
                "/_roots/action",
                view.cookie() + "; theme=green",
                actionForm(view, attribute(COOKIE_SET, initial.body()))
        );
        assertEquals(200, set.statusCode());
        assertTrue(set.body().contains("render=green; observed=green"), set.body());
        assertEquals(List.of("action-cookie=seen-green; Path=/; SameSite=Lax"),
                set.headers().allValues("Set-Cookie"));

        var delete = postForm(
                "/_roots/action",
                view.cookie() + "; theme=green; action-cookie=seen-green",
                actionForm(view, attribute(COOKIE_DELETE, initial.body()))
        );
        assertEquals(200, delete.statusCode());
        assertEquals(List.of("action-cookie=; Path=/; Max-Age=0; "
                        + "Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax"),
                delete.headers().allValues("Set-Cookie"));

        var failure = postForm(
                "/_roots/action",
                view.cookie() + "; theme=green",
                actionForm(view, attribute(COOKIE_FAIL, initial.body()))
        );
        assertEquals(418, failure.statusCode());
        assertTrue(failure.headers().allValues("Set-Cookie").isEmpty(), failure.headers().toString());
    }

    @Test
    void validatesActionProtocolCsrfSessionAndDeterministicDisposal() throws Exception {
        var initial = get("/");
        var view = browserView(initial);
        assertTrue(initial.body().contains("data-roots-protocol=\"" + Roots.PROTOCOL_VERSION + "\""));
        assertEquals(1, com.chaplin.roots.testapp.pages.Page.MOUNTS.get());
        assertEquals(1, com.chaplin.roots.testapp.pages.Layout.MOUNTS.get());
        assertEquals(1, application.runtimeSnapshot().liveViews());
        assertEquals(1, application.runtimeSnapshot().sessions());

        var incompatibleStream = send(HttpRequest.newBuilder(uri(
                        "/_roots/stream?view=" + encode(view.viewId()) + "&csrf=" + encode(view.csrf())
                                + "&protocol=999"))
                .header("Cookie", view.cookie())
                .GET()
                .build());
        assertEquals(200, incompatibleStream.statusCode());
        assertEquals(Roots.PROTOCOL_VERSION,
                incompatibleStream.headers().firstValue("X-Roots-Protocol").orElseThrow());
        assertTrue(incompatibleStream.body().contains("event: reload"), incompatibleStream.body());
        assertTrue(incompatibleStream.body().contains("\"protocol\":\"1\""), incompatibleStream.body());

        var missingProtocol = send(HttpRequest.newBuilder(uri("/_roots/action"))
                .header("Cookie", view.cookie())
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(actionForm(view, view.csrf())))
                .build());
        assertEquals(409, missingProtocol.statusCode());
        assertTrue(missingProtocol.body().contains("\"reload\":true"), missingProtocol.body());

        var malformed = send(HttpRequest.newBuilder(uri("/_roots/action"))
                .header("Cookie", view.cookie())
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("not-a-roots-form"))
                .build());
        assertEquals(400, malformed.statusCode());
        assertTrue(malformed.body().contains("Malformed Roots action request"));

        var unsupportedEvent = postForm(
                "/_roots/action",
                view.cookie(),
                actionForm(view, view.csrf()).replace("_event=click", "_event=wheel")
        );
        assertEquals(400, unsupportedEvent.statusCode());
        assertTrue(unsupportedEvent.body().contains("Malformed Roots action request"));

        var invalidEventMetadata = postForm(
                "/_roots/action",
                view.cookie(),
                actionForm(view, view.csrf()) + "&_event_alt=maybe"
        );
        assertEquals(400, invalidEventMetadata.statusCode());
        assertTrue(invalidEventMetadata.body().contains("Malformed Roots action request"));

        for (var invalidSuffix : List.of(
                "&_event_button=not-a-number",
                "&_event_key=one&_event_key=two"
        )) {
            var invalid = postForm(
                    "/_roots/action",
                    view.cookie(),
                    actionForm(view, view.csrf()) + invalidSuffix
            );
            assertEquals(400, invalid.statusCode());
            assertTrue(invalid.body().contains("Malformed Roots action request"));
        }

        var wrongSession = postForm("/_roots/action", null, actionForm(view, view.csrf()));
        assertEquals(409, wrongSession.statusCode());

        var wrongCsrf = postForm("/_roots/action", view.cookie(), actionForm(view, "wrong"));
        assertEquals(409, wrongCsrf.statusCode());

        trace.clear();
        var valid = postForm("/_roots/action", view.cookie(), actionForm(view, view.csrf()));
        assertEquals(200, valid.statusCode());
        assertEquals(Roots.PROTOCOL_VERSION, valid.headers().firstValue("X-Roots-Protocol").orElseThrow());
        assertTrue(valid.body().contains("\"protocol\":\"" + Roots.PROTOCOL_VERSION + "\""), valid.body());
        assertTrue(valid.body().contains("\"view\":\"" + view.viewId() + "\""), valid.body());
        assertTrue(valid.body().contains("Count 1"), valid.body());
        assertTrue(valid.body().contains("\"scope\":null"), valid.body());
        assertTrue(valid.body().contains("\"baseRevision\":1"), valid.body());
        assertTrue(valid.body().contains("\"revision\":2"), valid.body());
        assertTrue(trace.contains("outer-before:/"), trace.toString());
        assertFalse(trace.stream().anyMatch(entry -> entry.contains("/_roots/action")), trace.toString());

        var ignoredDisposal = postForm(
                "/_roots/dispose",
                view.cookie(),
                "_view=" + encode(view.viewId()) + "&_csrf=" + encode("wrong")
        );
        assertEquals(204, ignoredDisposal.statusCode());
        assertEquals(0, com.chaplin.roots.testapp.pages.Page.UNMOUNTS.get());

        var incompatibleDisposal = send(HttpRequest.newBuilder(uri("/_roots/dispose"))
                .header("Cookie", view.cookie())
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "_view=" + encode(view.viewId()) + "&_csrf=" + encode(view.csrf()) + "&_protocol=999"
                ))
                .build());
        assertEquals(409, incompatibleDisposal.statusCode());
        assertEquals(1, application.runtimeSnapshot().liveViews());
        assertEquals(0, com.chaplin.roots.testapp.pages.Page.UNMOUNTS.get());

        var incompatible = send(HttpRequest.newBuilder(uri("/_roots/action"))
                .header("Cookie", view.cookie())
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        actionForm(view, view.csrf()) + "&_protocol=999"
                ))
                .build());
        assertEquals(409, incompatible.statusCode());
        assertEquals(Roots.PROTOCOL_VERSION,
                incompatible.headers().firstValue("X-Roots-Protocol").orElseThrow());
        assertTrue(incompatible.body().contains("\"reload\":true"), incompatible.body());
        assertTrue(incompatible.body().contains("\"protocol\":\"1\""), incompatible.body());

        var disposed = postForm(
                "/_roots/dispose",
                view.cookie(),
                "_view=" + encode(view.viewId()) + "&_csrf=" + encode(view.csrf())
        );
        assertEquals(204, disposed.statusCode());
        assertEquals(1, com.chaplin.roots.testapp.pages.Page.UNMOUNTS.get());
        assertEquals(1, com.chaplin.roots.testapp.pages.Layout.UNMOUNTS.get());
        assertEquals(0, application.runtimeSnapshot().liveViews());

        var expired = postForm("/_roots/action", view.cookie(), actionForm(view, view.csrf()));
        assertEquals(409, expired.statusCode());
    }

    @Test
    void returnsOnlyAnExactlyIsolatedComponentForScopedActions() throws Exception {
        var initial = get("/scoped");
        var view = liveView(initial);
        var action = attribute(SCOPED_INCREMENT, initial.body());

        var response = postForm("/_roots/action", view.cookie(), actionForm(view, action));

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"scope\":\"c0\""), response.body());
        assertTrue(response.body().contains("\"baseRevision\":1"), response.body());
        assertTrue(response.body().contains("Scoped count 1"), response.body());
        assertFalse(response.body().contains("Outside injected dependency"), response.body());
        assertFalse(response.body().contains("data-layout"), response.body());
    }

    @Test
    void bindsActionRedirectsToTheirOriginatingView() throws Exception {
        var initial = get("/");
        var view = liveView(initial);
        var action = attribute(REDIRECT, initial.body());

        var response = postForm("/_roots/action", view.cookie(), actionForm(view, action));

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"protocol\":\"1\""), response.body());
        assertTrue(response.body().contains("\"view\":\"" + view.viewId() + "\""), response.body());
        assertTrue(response.body().contains("\"redirect\":\"/themed\""), response.body());
    }

    @Test
    void skipsDomTransferWhenAnActionDoesNotChangeRenderedHtml() throws Exception {
        var initial = get("/scoped");
        var view = liveView(initial);
        var action = attribute(SCOPED_NOOP, initial.body());

        var response = postForm("/_roots/action", view.cookie(), actionForm(view, action));

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"html\":null"), response.body());
        assertTrue(response.body().contains("\"scope\":null"), response.body());
        assertTrue(response.body().contains("\"baseRevision\":1"), response.body());
        assertTrue(response.body().contains("\"revision\":2"), response.body());
    }

    @Test
    void servesProtocolAssetsAndSecurityResponsesAtTheirBoundaries() throws Exception {
        assertEquals(0, application.runtimeSnapshot().sessions());

        var stylesheet = get("/base.css");
        assertEquals(200, stylesheet.statusCode());
        assertTrue(stylesheet.headers().firstValue("Content-Type").orElseThrow().startsWith("text/css"));
        assertEquals("public, max-age=3600", stylesheet.headers().firstValue("Cache-Control").orElseThrow());
        var stylesheetEtag = stylesheet.headers().firstValue("ETag").orElseThrow();
        assertTrue(stylesheetEtag.matches("\"[0-9a-f]{64}\""), stylesheetEtag);
        assertTrue(stylesheet.headers().firstValue("Set-Cookie").isEmpty());

        var cachedStylesheet = send(HttpRequest.newBuilder(uri("/base.css"))
                .header("If-None-Match", "\"not-this-one\", " + stylesheetEtag)
                .GET()
                .build());
        assertEquals(304, cachedStylesheet.statusCode());
        assertEquals("", cachedStylesheet.body());
        assertEquals(stylesheetEtag, cachedStylesheet.headers().firstValue("ETag").orElseThrow());

        var runtime = get("/_roots/client.js");
        assertEquals(200, runtime.statusCode());
        assertTrue(runtime.body().contains("disposeView"));
        assertTrue(runtime.body().contains("syncStylesheets"));
        assertTrue(runtime.body().contains("nextRoot.dataset.rootsProtocol !== previousRoot?.dataset.rootsProtocol"));
        var runtimeEtag = runtime.headers().firstValue("ETag").orElseThrow();
        assertTrue(runtime.headers().firstValue("Set-Cookie").isEmpty());

        var cachedRuntime = send(HttpRequest.newBuilder(uri("/_roots/client.js"))
                .header("If-None-Match", "W/" + runtimeEtag)
                .GET()
                .build());
        assertEquals(304, cachedRuntime.statusCode());
        assertEquals("", cachedRuntime.body());

        var wildcardRuntime = send(HttpRequest.newBuilder(uri("/_roots/client.js"))
                .header("If-None-Match", "*")
                .GET()
                .build());
        assertEquals(304, wildcardRuntime.statusCode());

        var missing = get("/missing%3Cscript%3E");
        assertEquals(404, missing.statusCode());
        assertFalse(missing.body().contains("<script>"), missing.body());
        assertTrue(missing.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
        assertEquals("nosniff", missing.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertTrue(missing.headers().firstValue("Set-Cookie").isEmpty());

        var assetPost = send(HttpRequest.newBuilder(uri("/base.css"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        assertEquals(405, assetPost.statusCode());
        assertEquals("GET, HEAD", assetPost.headers().firstValue("Allow").orElseThrow());

        var runtimePost = send(HttpRequest.newBuilder(uri("/_roots/client.js"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        assertEquals(405, runtimePost.statusCode());
        assertEquals("GET, HEAD", runtimePost.headers().firstValue("Allow").orElseThrow());

        var actionWithoutSession = postForm("/_roots/action", null, "");
        assertEquals(409, actionWithoutSession.statusCode());
        assertTrue(actionWithoutSession.headers().firstValue("Set-Cookie").isEmpty());

        var disposalWithoutSession = postForm("/_roots/dispose", null, "");
        assertEquals(204, disposalWithoutSession.statusCode());
        assertTrue(disposalWithoutSession.headers().firstValue("Set-Cookie").isEmpty());

        var streamWithoutSession = get("/_roots/stream");
        assertEquals(404, streamWithoutSession.statusCode());
        assertTrue(streamWithoutSession.headers().firstValue("Set-Cookie").isEmpty());

        assertEquals(0, application.runtimeSnapshot().sessions());
        assertTrue(trace.isEmpty(), trace.toString());

        var viewsBeforeHead = application.runtimeSnapshot().liveViews();
        var head = send(HttpRequest.newBuilder(uri("/")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals(viewsBeforeHead, application.runtimeSnapshot().liveViews());

        var unsupported = send(HttpRequest.newBuilder(uri("/")).PUT(HttpRequest.BodyPublishers.noBody()).build());
        assertEquals(405, unsupported.statusCode());
        assertEquals("GET, HEAD", unsupported.headers().firstValue("Allow").orElseThrow());

        var disposeGet = get("/_roots/dispose");
        assertEquals(405, disposeGet.statusCode());
        assertEquals("POST", disposeGet.headers().firstValue("Allow").orElseThrow());

        var largeBody = "x".repeat(1_048_577);
        // Test early rejection without racing an unread upload against the JDK transport's close.
        try (var socket = new Socket(application.uri().getHost(), application.uri().getPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(("POST /api/items/42 HTTP/1.1\r\n"
                    + "Host: localhost\r\nContent-Length: " + largeBody.length()
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            var statusLine = reader.readLine();
            assertTrue(statusLine != null && statusLine.startsWith("HTTP/1.1 413 "), String.valueOf(statusLine));
        }
        // Unknown length exercises the byte limit while actually consuming the streamed body.
        var tooLarge = send(HttpRequest.newBuilder(uri("/api/items/42"))
                .POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(largeBody)))
                .build());
        assertEquals(413, tooLarge.statusCode());
    }

    @Test
    void scannerTrafficCannotConsumeSessionCapacity() throws Exception {
        try (var limited = limitedApplication(1, 1)) {
            for (var batch = 0; batch < 4; batch++) {
                var offset = batch * 24;
                var requests = IntStream.range(0, 24)
                        .mapToObj(index -> CLIENT.sendAsync(
                                HttpRequest.newBuilder(limited.uri().resolve("/scanner-miss-" + (offset + index)))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString()
                        ))
                        .toList();
                CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
                assertTrue(requests.stream().map(CompletableFuture::join)
                        .allMatch(response -> response.statusCode() == 404
                                && response.headers().firstValue("Set-Cookie").isEmpty()));
            }

            var invalidAction = CLIENT.send(
                    HttpRequest.newBuilder(limited.uri().resolve("/_roots/action"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            var invalidDisposal = CLIENT.send(
                    HttpRequest.newBuilder(limited.uri().resolve("/_roots/dispose"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            var invalidStream = CLIENT.send(
                    HttpRequest.newBuilder(limited.uri().resolve("/_roots/stream"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(409, invalidAction.statusCode());
            assertEquals(204, invalidDisposal.statusCode());
            assertEquals(404, invalidStream.statusCode());
            assertEquals(0, limited.runtimeSnapshot().sessions());
            assertEquals(0, limited.runtimeSnapshot().rejectedRequests());

            var page = CLIENT.send(
                    HttpRequest.newBuilder(limited.uri().resolve("/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, page.statusCode());
            assertEquals(1, limited.runtimeSnapshot().sessions());
            assertEquals(1, limited.runtimeSnapshot().liveViews());
        }
    }

    @Test
    void parsesApiFormsAndIssuesHardenedSessionCookie() throws Exception {
        var response = postForm("/api/items/encoded%20id", null, "name=" + encode("Ada Lovelace"));

        assertEquals(201, response.statusCode());
        assertEquals("Ada Lovelace", response.body());
        var cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly"), cookie);
        assertTrue(cookie.contains("SameSite=Lax"), cookie);
        assertTrue(cookie.contains("Path=/"), cookie);
        assertTrue(cookie.contains("Secure"), cookie);
    }

    @Test
    void parsesMultipartApiUploadsAndRejectsMalformedOrOversizedBodies() throws Exception {
        var boundary = "RootsHttpBoundary4d2";
        var binary = concat(
                new byte[]{0, (byte) 0xff, 7},
                ("near\r\n--" + boundary + "X\r\nend").getBytes(StandardCharsets.UTF_8)
        );
        var body = multipart(boundary,
                multipartPart("Content-Disposition: form-data; name=\"title\"", "Binary evidence".getBytes(StandardCharsets.UTF_8)),
                multipartPart("Content-Disposition: form-data; name=\"attachments\"; filename=\"../../proof.bin\"\r\n"
                        + "Content-Type: application/octet-stream", binary),
                multipartPart("Content-Disposition: form-data; name=\"attachments\"; filename=\"empty.txt\"\r\n"
                        + "Content-Type: text/plain", new byte[0])
        );
        var uploaded = send(HttpRequest.newBuilder(uri("/api/upload"))
                .header("Content-Type", "multipart/form-data; boundary=\"" + boundary + "\"")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build());

        assertEquals(200, uploaded.statusCode());
        assertEquals("Binary evidence|2|../../proof.bin|application/octet-stream|" + binary.length
                + "|" + java.util.HexFormat.of().formatHex(binary) + "|empty.txt", uploaded.body());

        var cookie = uploaded.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        var malformed = send(HttpRequest.newBuilder(uri("/api/upload"))
                .header("Cookie", cookie)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "--" + boundary + "\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nmissing close"
                ))
                .build());
        assertEquals(400, malformed.statusCode());
        assertTrue(malformed.body().contains("closing boundary"), malformed.body());

        var missingBoundary = send(HttpRequest.newBuilder(uri("/api/upload"))
                .header("Cookie", cookie)
                .header("Content-Type", "multipart/form-data")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[0]))
                .build());
        assertEquals(400, missingBoundary.statusCode());

        try (var limited = requestLimitedApplication(64)) {
            var oversized = CLIENT.send(
                    HttpRequest.newBuilder(limited.uri().resolve("/api/upload"))
                            .header("Content-Type", "application/octet-stream")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[65]))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(413, oversized.statusCode());
            assertEquals(1, limited.runtimeSnapshot().sessions());
        }
    }

    @Test
    void spoolsLargeMultipartBodiesAndReleasesRequestScopedContent() throws Exception {
        var boundary = "roots-spool-boundary";
        var binary = new byte[140_000];
        for (var index = 0; index < binary.length; index++) {
            binary[index] = (byte) (index * 17);
        }
        var falseBoundary = ("\r\n--" + boundary + "X-not-a-boundary")
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(falseBoundary, 0, binary, 70_000, falseBoundary.length);
        var body = multipart(boundary,
                multipartPart("Content-Disposition: form-data; name=\"title\"",
                        "Large evidence".getBytes(StandardCharsets.UTF_8)),
                multipartPart("Content-Disposition: form-data; name=\"attachment\"; filename=\"large.bin\"\r\n"
                        + "Content-Type: application/octet-stream", binary));
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(binary);

        var response = CLIENT.send(
                HttpRequest.newBuilder(uri("/api/spool"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"requestBytes\":" + body.length), response.body());
        assertTrue(response.body().contains("\"requestInMemory\":false"), response.body());
        assertTrue(response.body().contains("\"uploadBytes\":" + binary.length), response.body());
        assertTrue(response.body().contains("\"uploadInMemory\":false"), response.body());
        assertTrue(response.body().contains("\"title\":\"Large evidence\""), response.body());
        assertTrue(response.body().contains(java.util.HexFormat.of().formatHex(digest)), response.body());
        assertThrows(java.io.IOException.class,
                () -> com.chaplin.roots.testapp.api.spool.Route.lastUpload().openStream());
    }

    @Test
    void closeReleasesAwaitersAndClearsObservableRuntimeState() throws Exception {
        assertEquals(200, get("/").statusCode());
        await(Duration.ofSeconds(2), () -> application.runtimeSnapshot().handledRequests() >= 1);
        var before = application.runtimeSnapshot();
        assertTrue(before.running());
        assertTrue(before.handledRequests() >= 1);
        assertEquals(1, before.liveViews());
        assertEquals(1, before.sessions());

        var awaited = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                application.await();
                awaited.complete(null);
            } catch (InterruptedException exception) {
                awaited.completeExceptionally(exception);
            }
        });
        application.close();
        awaited.get(2, TimeUnit.SECONDS);

        var after = application.runtimeSnapshot();
        assertFalse(after.running());
        assertEquals(0, after.liveViews());
        assertEquals(0, after.sessions());
        await(Duration.ofSeconds(2), () -> com.chaplin.roots.testapp.pages.Page.UNMOUNTS.get() == 1
                && com.chaplin.roots.testapp.pages.Layout.UNMOUNTS.get() == 1);
    }

    @Test
    void replacementAndDisposalWakeSseStreamsWithoutWaitingForAHeartbeat() throws Exception {
        var credentials = liveView(get("/"));
        var streamRequest = HttpRequest.newBuilder(uri(
                        "/_roots/stream?view=" + encode(credentials.viewId()) + "&csrf=" + encode(credentials.csrf())
                                + "&protocol=" + encode(Roots.PROTOCOL_VERSION)))
                .header("Cookie", credentials.cookie())
                .method("GET", HttpRequest.BodyPublishers.ofByteArray(new byte[70_000]))
                .build();
        var stream = CLIENT.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());

        try (var body = stream.body(); var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            assertEquals(": connected", reader.readLine());
            assertEquals("", reader.readLine());
            assertEquals("event: patch", reader.readLine());
            assertTrue(reader.readLine().startsWith("data: {\"protocol\":\"1\",\"view\":\""
                    + credentials.viewId() + "\",\"html\":"));
            assertEquals("", reader.readLine());

            var replacement = CLIENT.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, replacement.statusCode());
            try (var replacementBody = replacement.body();
                 var replacementReader = new BufferedReader(new InputStreamReader(
                         replacementBody, StandardCharsets.UTF_8))) {
                assertEquals(": connected", replacementReader.readLine());
                assertEquals("", replacementReader.readLine());
                assertEquals("event: patch", replacementReader.readLine());
                assertTrue(replacementReader.readLine().startsWith("data: {\"protocol\":\"1\",\"view\":\""
                        + credentials.viewId() + "\",\"html\":"));
                assertEquals("", replacementReader.readLine());

                var replacedEnd = new CompletableFuture<String>();
                Thread.startVirtualThread(() -> {
                    try {
                        replacedEnd.complete(reader.readLine());
                    } catch (Exception exception) {
                        replacedEnd.completeExceptionally(exception);
                    }
                });
                assertNull(replacedEnd.get(2, TimeUnit.SECONDS));

                var disposed = postForm(
                        "/_roots/dispose",
                        credentials.cookie(),
                        "_view=" + encode(credentials.viewId()) + "&_csrf=" + encode(credentials.csrf())
                );
                assertEquals(204, disposed.statusCode());

                var endOfStream = new CompletableFuture<String>();
                Thread.startVirtualThread(() -> {
                    try {
                        endOfStream.complete(replacementReader.readLine());
                    } catch (Exception exception) {
                        endOfStream.completeExceptionally(exception);
                    }
                });
                assertNull(endOfStream.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void oneFailingUnmountCallbackDoesNotBlockRemainingCleanup() throws Exception {
        var credentials = liveView(get("/lifecycle"));
        assertEquals(2, com.chaplin.roots.testapp.components.LifecycleProbe.MOUNTS.get());
        assertEquals(1, com.chaplin.roots.testapp.pages.lifecycle.Page.MOUNTS.get());

        var disposed = postForm(
                "/_roots/dispose",
                credentials.cookie(),
                "_view=" + encode(credentials.viewId()) + "&_csrf=" + encode(credentials.csrf())
        );

        assertEquals(204, disposed.statusCode());
        assertEquals(2, com.chaplin.roots.testapp.components.LifecycleProbe.UNMOUNTS.get());
        assertEquals(1, com.chaplin.roots.testapp.pages.lifecycle.Page.UNMOUNTS.get());
        assertEquals(1, com.chaplin.roots.testapp.pages.Layout.UNMOUNTS.get());
        assertEquals(0, application.runtimeSnapshot().liveViews());
    }

    @Test
    void oneFailingUnmountDuringRerenderDoesNotCorruptLifecycleState() throws Exception {
        var initial = get("/lifecycle");
        var credentials = liveView(initial);
        assertEquals(2, com.chaplin.roots.testapp.components.LifecycleProbe.MOUNTS.get());

        var removal = postForm(
                "/_roots/action",
                credentials.cookie(),
                actionForm(credentials, attribute(REMOVE_PROBES, initial.body()))
        );

        assertEquals(200, removal.statusCode());
        assertEquals(2, com.chaplin.roots.testapp.components.LifecycleProbe.UNMOUNTS.get());
        assertEquals(0, com.chaplin.roots.testapp.pages.lifecycle.Page.UNMOUNTS.get());
        assertEquals(1, application.runtimeSnapshot().liveViews());

        postForm(
                "/_roots/dispose",
                credentials.cookie(),
                "_view=" + encode(credentials.viewId()) + "&_csrf=" + encode(credentials.csrf())
        );
        assertEquals(2, com.chaplin.roots.testapp.components.LifecycleProbe.UNMOUNTS.get());
        assertEquals(1, com.chaplin.roots.testapp.pages.lifecycle.Page.UNMOUNTS.get());
    }

    @Test
    void concurrentActionsSerializeWithoutLostUpdatesOrDuplicateRevisions() throws Exception {
        var view = browserView(get("/"));
        var form = actionForm(view, view.csrf());
        var requests = IntStream.range(0, 32)
                .mapToObj(ignored -> CLIENT.sendAsync(
                        postFormRequest("/_roots/action", view.cookie(), form),
                        HttpResponse.BodyHandlers.ofString()
                ))
                .toList();

        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        var responses = requests.stream().map(CompletableFuture::join).toList();
        assertTrue(responses.stream().allMatch(response -> response.statusCode() == 200));

        var revisions = new TreeSet<Integer>();
        for (var response : responses) {
            var matcher = REVISION.matcher(response.body());
            assertTrue(matcher.find(), response.body());
            revisions.add(Integer.parseInt(matcher.group(1)));
        }
        assertEquals(32, revisions.size());
        assertEquals(2, revisions.getFirst());
        assertEquals(33, revisions.getLast());
        assertTrue(responses.stream().anyMatch(response -> response.body().contains("Count 32")));
    }

    @Test
    void mapsDomainFailuresAndReturnsStructuredFieldValidation() throws Exception {
        var initial = get("/");
        var credentials = liveView(initial);

        var mapped = postForm(
                "/_roots/action",
                credentials.cookie(),
                actionForm(credentials, attribute(EXPLODE, initial.body()))
        );
        assertEquals(418, mapped.statusCode());
        assertEquals(
                "{\"error\":\"mapped\",\"logicalPath\":\"/\",\"transportPath\":\"/_roots/action\"}",
                mapped.body()
        );
        assertEquals("uncertain", mapped.headers().firstValue("X-Roots-Action-Outcome").orElseThrow());
        var blocked = postForm("/_roots/action", credentials.cookie(),
                actionForm(credentials, attribute(VALIDATE, initial.body())));
        assertEquals(409, blocked.statusCode());

        initial = get("/");
        credentials = liveView(initial);

        var validation = postForm(
                "/_roots/action",
                credentials.cookie(),
                actionForm(credentials, attribute(VALIDATE, initial.body()))
        );
        assertEquals(422, validation.statusCode());
        assertTrue(validation.body().contains("\"error\":\"Please fix the highlighted fields\""), validation.body());
        assertTrue(validation.body().contains("\"email\":[\"Enter a valid email\",\"Email must not contain \\u003cmarkup\\u003e\"]"), validation.body());
        assertTrue(validation.body().contains("\"name\":[\"Name is required\"]"), validation.body());
        assertEquals("no-store", validation.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void healthIsSessionFreeAndDrainChangesReadinessBeforeShutdown() throws Exception {
        var health = get("/_roots/health");
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"status\":\"UP\""), health.body());
        assertTrue(health.body().contains("\"ready\":true"), health.body());
        assertTrue(health.body().contains("\"node\":\"" + application.nodeId() + "\""), health.body());
        assertFalse(health.body().contains("liveViews"), health.body());
        assertTrue(health.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(0, application.runtimeSnapshot().sessions());
        assertTrue(trace.isEmpty(), trace.toString());

        var head = send(HttpRequest.newBuilder(uri("/_roots/health"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build());
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());

        var unsupported = send(HttpRequest.newBuilder(uri("/_roots/health"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        assertEquals(405, unsupported.statusCode());
        assertEquals("GET, HEAD", unsupported.headers().firstValue("Allow").orElseThrow());
        assertEquals(0, application.runtimeSnapshot().sessions());

        application.beginDrain();
        var draining = get("/_roots/health");
        assertEquals(503, draining.statusCode());
        assertTrue(draining.body().contains("\"status\":\"DRAINING\""), draining.body());
        assertTrue(draining.body().contains("\"ready\":false"), draining.body());

        var rejected = get("/");
        assertEquals(503, rejected.statusCode());
        assertEquals("1", rejected.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(rejected.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(0, application.runtimeSnapshot().sessions());
        assertEquals(1, application.runtimeSnapshot().rejectedRequests());
        assertThrows(IllegalArgumentException.class, () -> application.closeGracefully(Duration.ofMillis(-1)));

        application.closeGracefully(Duration.ofSeconds(1));
        var stopped = application.runtimeSnapshot();
        assertFalse(stopped.running());
        assertFalse(stopped.acceptingRequests());
        assertEquals(0, stopped.activeRequests());
        assertTrue(stopped.peakActiveRequests() >= 1);
    }

    @Test
    void viewAndSessionAdmissionLimitsRejectAndRecoverDeterministically() throws Exception {
        try (var limited = limitedApplication(1, 1)) {
            var initial = CLIENT.send(
                    HttpRequest.newBuilder(limited.uri().resolve("/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            var first = liveView(initial);
            assertEquals(1, limited.runtimeSnapshot().liveViews());
            assertEquals(1, limited.runtimeSnapshot().sessions());

            var sessionRejected = CLIENT.send(
                    HttpRequest.newBuilder(limited.uri().resolve("/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(503, sessionRejected.statusCode());
            assertTrue(sessionRejected.body().contains("Session capacity is exhausted"), sessionRejected.body());
            assertTrue(sessionRejected.headers().firstValue("Set-Cookie").isEmpty());

            var viewRejected = CLIENT.send(
                    HttpRequest.newBuilder(limited.uri().resolve("/themed"))
                            .header("Cookie", first.cookie())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(503, viewRejected.statusCode());
            assertTrue(viewRejected.body().contains("Live view capacity is exhausted"), viewRejected.body());
            assertEquals(1, limited.runtimeSnapshot().liveViews());
            assertEquals(2, limited.runtimeSnapshot().rejectedRequests());

            var disposed = CLIENT.send(
                    HttpRequest.newBuilder(limited.uri().resolve("/_roots/dispose"))
                            .header("Cookie", first.cookie())
                            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "_view=" + encode(first.viewId()) + "&_csrf=" + encode(first.csrf())
                                            + "&_protocol=" + encode(Roots.PROTOCOL_VERSION)
                            ))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(204, disposed.statusCode());
            assertEquals(0, limited.runtimeSnapshot().liveViews());

            var failedConstruction = CLIENT.send(
                    HttpRequest.newBuilder(limited.uri().resolve("/broken"))
                            .header("Cookie", first.cookie())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(500, failedConstruction.statusCode());
            assertEquals("broken page fixture", failedConstruction.body());
            assertEquals(0, limited.runtimeSnapshot().liveViews());

            var admittedAgain = CLIENT.send(
                    HttpRequest.newBuilder(limited.uri().resolve("/themed"))
                            .header("Cookie", first.cookie())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, admittedAgain.statusCode());
            assertEquals(1, limited.runtimeSnapshot().liveViews());
            limited.closeGracefully(Duration.ofSeconds(5));
        }
    }

    @Test
    void requestAdmissionLimitRejectsAConcurrentBurstAndRecovers() throws Exception {
        try (var limited = limitedApplication(1, 10, 2)) {
            com.chaplin.roots.testapp.api.blocking.Route.reset();
            var firstBlocking = CLIENT.sendAsync(
                    HttpRequest.newBuilder(limited.uri().resolve("/api/blocking")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertTrue(com.chaplin.roots.testapp.api.blocking.Route.awaitEntry());
            var secondBlocking = CLIENT.sendAsync(
                    HttpRequest.newBuilder(limited.uri().resolve("/api/blocking")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            await(Duration.ofSeconds(2), () -> limited.runtimeSnapshot().activeRequests() == 2);

            var saturated = CLIENT.send(
                    HttpRequest.newBuilder(limited.uri().resolve("/base.css")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(503, saturated.statusCode());
            assertTrue(saturated.body().contains("Request capacity is exhausted"), saturated.body());
            assertEquals("1", saturated.headers().firstValue("Retry-After").orElseThrow());
            assertTrue(saturated.headers().firstValue("Set-Cookie").isEmpty());
            assertEquals(2, limited.runtimeSnapshot().activeRequests());
            assertEquals(2, limited.runtimeSnapshot().peakActiveRequests());
            assertEquals(2, limited.runtimeSnapshot().maxConcurrentRequests());
            assertEquals(1, limited.runtimeSnapshot().rejectedRequests());

            com.chaplin.roots.testapp.api.blocking.Route.release();
            assertEquals(200, firstBlocking.get(2, TimeUnit.SECONDS).statusCode());
            assertEquals(200, secondBlocking.get(2, TimeUnit.SECONDS).statusCode());
            await(Duration.ofSeconds(2), () -> limited.runtimeSnapshot().activeRequests() == 0);

            var recovered = CLIENT.send(
                    HttpRequest.newBuilder(limited.uri().resolve("/base.css")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, recovered.statusCode());
            await(Duration.ofSeconds(2), () -> limited.runtimeSnapshot().activeRequests() == 0);
            assertEquals(0, limited.runtimeSnapshot().activeRequests());
        } finally {
            com.chaplin.roots.testapp.api.blocking.Route.release();
        }
    }

    @Test
    void parallelActionsRemainIsolatedAcrossMultipleViews() throws Exception {
        var viewCount = 6;
        var actionsPerView = 8;
        var views = new ArrayList<BrowserView>();
        var groups = new ArrayList<List<CompletableFuture<HttpResponse<String>>>>();
        com.chaplin.roots.testapp.pages.Page.blockParallelActions(viewCount);
        try {
            for (var viewIndex = 0; viewIndex < viewCount; viewIndex++) {
                var view = browserView(get("/"));
                views.add(view);
                var form = actionForm(view, view.csrf());
                var group = new ArrayList<CompletableFuture<HttpResponse<String>>>();
                for (var actionIndex = 0; actionIndex < actionsPerView; actionIndex++) {
                    group.add(CLIENT.sendAsync(
                            postFormRequest("/_roots/action", view.cookie(), form),
                            HttpResponse.BodyHandlers.ofString()
                    ));
                }
                groups.add(List.copyOf(group));
            }
            assertTrue(com.chaplin.roots.testapp.pages.Page.awaitParallelActions());
            assertTrue(application.runtimeSnapshot().peakActiveRequests() >= viewCount);
        } finally {
            com.chaplin.roots.testapp.pages.Page.releaseParallelActions();
        }

        var all = groups.stream().flatMap(List::stream).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(all).get(10, TimeUnit.SECONDS);
        for (var group : groups) {
            var responses = group.stream().map(CompletableFuture::join).toList();
            assertTrue(responses.stream().allMatch(response -> response.statusCode() == 200),
                    () -> responses.stream().map(response -> response.statusCode() + ":" + response.body())
                            .collect(java.util.stream.Collectors.joining("\n")));
            var revisions = new TreeSet<Integer>();
            for (var response : responses) {
                var matcher = REVISION.matcher(response.body());
                assertTrue(matcher.find(), response.body());
                revisions.add(Integer.parseInt(matcher.group(1)));
            }
            assertEquals(actionsPerView, revisions.size());
            assertEquals(2, revisions.getFirst());
            assertEquals(actionsPerView + 1, revisions.getLast());
            assertTrue(responses.stream().anyMatch(response -> response.body().contains("Count " + actionsPerView)));
        }
        assertEquals(viewCount, application.runtimeSnapshot().liveViews());

        for (var view : views) {
            assertEquals(204, postForm(
                    "/_roots/dispose",
                    view.cookie(),
                    "_view=" + encode(view.viewId()) + "&_csrf=" + encode(view.csrf())
            ).statusCode());
        }
        assertEquals(0, application.runtimeSnapshot().liveViews());
    }

    @Test
    void sharedOwnershipIdentifiesTheCorrectNodeWithoutLeakingAcrossSessions() throws Exception {
        var sessions = SessionRepository.inMemory();
        var leases = new ConcurrentHashMap<String, LiveViewOwner>();
        var nodeAOwnership = new SharedOwnership("node-a", leases);
        var nodeBOwnership = new SharedOwnership("node-b", leases);
        InstanceFactory factory = type -> type.getDeclaredConstructor(String.class).newInstance("cluster dependency");
        try (var nodeA = Roots.start(RootsConfig.forApplication(Application.class)
                .port(0).development(false).sessionRepository(sessions)
                .liveViewOwnership(nodeAOwnership).instanceFactory(factory).build());
             var nodeB = Roots.start(RootsConfig.forApplication(Application.class)
                     .port(0).development(false).sessionRepository(sessions)
                     .liveViewOwnership(nodeBOwnership).instanceFactory(factory)
                     .use((request, chain) -> request.transportPath().equals("/_roots/action")
                             ? Response.text(418, "middleware intercepted action") : chain.next())
                     .build())) {
            var initial = CLIENT.send(HttpRequest.newBuilder(nodeA.uri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var view = new BrowserView(
                    initial.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                    attribute(VIEW, initial.body()), attribute(CSRF, initial.body()), attribute(INCREMENT, initial.body()));
            assertEquals("node-a", initial.headers().firstValue("X-Roots-Node").orElseThrow());

            var ownedAction = CLIENT.send(clusterAction(nodeA.uri(), view), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ownedAction.statusCode(), ownedAction.body());
            assertTrue(nodeAOwnership.renewals.get() > 0);

            var wrongNode = CLIENT.send(clusterAction(nodeB.uri(), view), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, wrongNode.statusCode(), wrongNode.body());
            assertEquals("node-a", wrongNode.headers().firstValue("X-Roots-Owner").orElseThrow());
            assertTrue(wrongNode.body().contains("\"retry\":true"), wrongNode.body());

            var wrongStream = CLIENT.send(HttpRequest.newBuilder(URI.create(nodeB.uri() + "/_roots/stream?view="
                            + encode(view.viewId()) + "&csrf=" + encode(view.csrf())
                            + "&protocol=" + encode(Roots.PROTOCOL_VERSION)))
                    .header("Cookie", view.cookie()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, wrongStream.statusCode(), wrongStream.body());
            assertEquals("node-a", wrongStream.headers().firstValue("X-Roots-Owner").orElseThrow());

            var otherSession = CLIENT.send(HttpRequest.newBuilder(nodeB.uri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var otherCookie = otherSession.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            var concealed = CLIENT.send(HttpRequest.newBuilder(nodeB.uri().resolve("_roots/action"))
                            .header("Cookie", otherCookie)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(actionForm(view, view.csrf())
                                    + "&_protocol=" + encode(Roots.PROTOCOL_VERSION))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(418, concealed.statusCode());
            assertTrue(concealed.headers().firstValue("X-Roots-Owner").isEmpty());

            assertEquals(204, CLIENT.send(HttpRequest.newBuilder(nodeA.uri().resolve("_roots/dispose"))
                            .header("Cookie", view.cookie())
                            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString("_view=" + encode(view.viewId())
                                    + "&_csrf=" + encode(view.csrf())
                                    + "&_protocol=" + encode(Roots.PROTOCOL_VERSION))).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(nodeAOwnership.find(view.viewId(), Instant.now()).isEmpty());
            // Lease release runs with view cleanup; close() alone has a zero-length grace period.
            nodeB.closeGracefully(Duration.ofSeconds(5));
            nodeA.closeGracefully(Duration.ofSeconds(5));
        }
        assertTrue(leases.isEmpty(), leases.toString());
    }

    @Test
    void ownershipOutageFailsPageCreationClosed() throws Exception {
        LiveViewOwnership unavailable = new SharedOwnership("offline-node", new ConcurrentHashMap<>()) {
            @Override
            public boolean claim(String viewId, String sessionId, Instant now, Instant expiresAt) {
                throw new LiveViewOwnershipException("offline");
            }
        };
        InstanceFactory factory = type -> type.getDeclaredConstructor(String.class).newInstance("ownership failure");
        try (var node = Roots.start(RootsConfig.forApplication(Application.class)
                .port(0).development(false).liveViewOwnership(unavailable).instanceFactory(factory).build())) {
            var response = CLIENT.send(HttpRequest.newBuilder(node.uri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode(), response.body());
            assertTrue(response.body().contains("ownership registry is unavailable"), response.body());
            assertEquals(0, node.runtimeSnapshot().liveViews());
        }
    }

    @Test
    void idleExpiryReleasesTheExternalOwnershipLease() throws Exception {
        var leases = new ConcurrentHashMap<String, LiveViewOwner>();
        var ownership = new SharedOwnership("expiring-node", leases);
        InstanceFactory factory = type -> type.getDeclaredConstructor(String.class).newInstance("expiring ownership");
        try (var node = Roots.start(RootsConfig.forApplication(Application.class)
                .port(0).development(false)
                .viewTimeout(Duration.ofMillis(60)).sessionTimeout(Duration.ofSeconds(2))
                .liveViewOwnership(ownership).instanceFactory(factory).build())) {
            var initial = CLIENT.send(HttpRequest.newBuilder(node.uri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var viewId = attribute(VIEW, initial.body());
            assertTrue(ownership.find(viewId, Instant.now()).isPresent());
            await(Duration.ofSeconds(2), () -> node.runtimeSnapshot().liveViews() == 0);
            assertTrue(ownership.find(viewId, Instant.now()).isEmpty());
        }
    }

    @Test
    void gracefulShutdownWaitsForAnAcceptedRequestAndThenReleasesAwaiters() throws Exception {
        com.chaplin.roots.testapp.api.blocking.Route.reset();
        var request = CLIENT.sendAsync(
                HttpRequest.newBuilder(uri("/api/blocking")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(com.chaplin.roots.testapp.api.blocking.Route.awaitEntry());
        assertTrue(application.runtimeSnapshot().activeRequests() >= 1);

        var awaited = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                application.await();
                awaited.complete(null);
            } catch (InterruptedException exception) {
                awaited.completeExceptionally(exception);
            }
        });
        var draining = CompletableFuture.runAsync(
                () -> application.closeGracefully(Duration.ofSeconds(2))
        );
        try {
            Thread.sleep(50);
            assertFalse(draining.isDone(), "Graceful shutdown returned before the accepted request completed");
            assertFalse(awaited.isDone(), "Application awaiter released before shutdown completed");
        } finally {
            com.chaplin.roots.testapp.api.blocking.Route.release();
        }

        assertEquals(200, request.get(2, TimeUnit.SECONDS).statusCode());
        draining.get(2, TimeUnit.SECONDS);
        awaited.get(2, TimeUnit.SECONDS);
        assertFalse(application.runtimeSnapshot().running());
        assertEquals(0, application.runtimeSnapshot().activeRequests());
    }

    @Test
    void zeroTimeoutShutdownCutsOffAStuckRequestWithoutBlockingTheCaller() throws Exception {
        try (var cutoff = limitedApplication(10, 10)) {
            com.chaplin.roots.testapp.api.blocking.Route.reset();
            var request = CLIENT.sendAsync(
                    HttpRequest.newBuilder(cutoff.uri().resolve("/api/blocking")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertTrue(com.chaplin.roots.testapp.api.blocking.Route.awaitEntry());

            var started = System.nanoTime();
            try {
                cutoff.closeGracefully(Duration.ZERO);
            } finally {
                com.chaplin.roots.testapp.api.blocking.Route.release();
            }
            var elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) < 0, "Immediate shutdown took " + elapsed);
            await(Duration.ofSeconds(2), () -> cutoff.runtimeSnapshot().activeRequests() == 0);
            assertFalse(cutoff.runtimeSnapshot().running());
            request.handle((ignored, failure) -> null).get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void scheduledMaintenanceExpiresQuietViewsAndRunsTheirLifecycleCleanup() throws Exception {
        com.chaplin.roots.testapp.pages.Page.reset();
        com.chaplin.roots.testapp.pages.Layout.reset();
        try (var expiring = expiringApplication(Duration.ofMillis(50), Duration.ofSeconds(10))) {
            var initial = CLIENT.send(
                    HttpRequest.newBuilder(expiring.uri().resolve("/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, initial.statusCode());
            assertEquals(1, expiring.runtimeSnapshot().liveViews());

            await(Duration.ofSeconds(2), () -> expiring.runtimeSnapshot().liveViews() == 0
                    && com.chaplin.roots.testapp.pages.Page.UNMOUNTS.get() == 1
                    && com.chaplin.roots.testapp.pages.Layout.UNMOUNTS.get() == 1);

            assertEquals(1, com.chaplin.roots.testapp.pages.Page.UNMOUNTS.get());
            assertEquals(1, com.chaplin.roots.testapp.pages.Layout.UNMOUNTS.get());
        }
    }

    @Test
    void expiredSessionCookiesCannotInvokeTheirOldViewOrCreateProtocolSessions() throws Exception {
        try (var expiring = expiringApplication(Duration.ofSeconds(10), Duration.ofMillis(50))) {
            var initial = CLIENT.send(
                    HttpRequest.newBuilder(expiring.uri().resolve("/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            var view = browserView(initial);
            await(Duration.ofSeconds(2), () -> expiring.runtimeSnapshot().sessions() == 0);

            var expiredAction = CLIENT.send(
                    HttpRequest.newBuilder(expiring.uri().resolve("/_roots/action"))
                            .header("Cookie", view.cookie())
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(actionForm(view, view.csrf())))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(409, expiredAction.statusCode());
            assertTrue(expiredAction.headers().firstValue("Set-Cookie").isEmpty());
            assertEquals(0, expiring.runtimeSnapshot().sessions());
            expiring.closeGracefully(Duration.ofSeconds(5));
            await(Duration.ofSeconds(2), () -> com.chaplin.roots.testapp.pages.Page.UNMOUNTS.get() == 1
                    && com.chaplin.roots.testapp.pages.Layout.UNMOUNTS.get() == 1);
        }
    }

    private BrowserView browserView(HttpResponse<String> response) {
        var credentials = liveView(response);
        return new BrowserView(
                credentials.cookie(),
                credentials.viewId(),
                credentials.csrf(),
                attribute(INCREMENT, response.body())
        );
    }

    private LiveViewCredentials liveView(HttpResponse<String> response) {
        return new LiveViewCredentials(
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                attribute(VIEW, response.body()),
                attribute(CSRF, response.body())
        );
    }

    private String actionForm(BrowserView view, String csrf) {
        return "_view=" + encode(view.viewId())
                + "&_csrf=" + encode(csrf)
                + "&_action=" + encode(view.action())
                + "&_event=click";
    }

    private String actionForm(LiveViewCredentials view, String action) {
        return "_view=" + encode(view.viewId())
                + "&_csrf=" + encode(view.csrf())
                + "&_action=" + encode(action)
                + "&_event=click";
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpResponse<String> postForm(String path, String cookie, String form) throws Exception {
        return send(postFormRequest(path, cookie, form));
    }

    private HttpRequest postFormRequest(String path, String cookie, String form) {
        var protocolForm = form + (form.isEmpty() ? "" : "&")
                + "_protocol=" + encode(Roots.PROTOCOL_VERSION);
        var builder = HttpRequest.newBuilder(uri(path))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(protocolForm));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return builder.build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return application.uri().resolve(path);
    }

    private static RunningApplication expiringApplication(Duration viewTimeout, Duration sessionTimeout) {
        InstanceFactory factory = type -> type.getDeclaredConstructor(String.class)
                .newInstance("timeout dependency");
        return Roots.start(RootsConfig.forApplication(Application.class)
                .port(0)
                .development(false)
                .viewTimeout(viewTimeout)
                .sessionTimeout(sessionTimeout)
                .maxSessions(1)
                .instanceFactory(factory)
                .build());
    }

    private static RunningApplication limitedApplication(int maxLiveViews, int maxSessions) {
        return limitedApplication(maxLiveViews, maxSessions, 20_000);
    }

    private static RunningApplication requestLimitedApplication(int maxRequestBytes) {
        InstanceFactory factory = type -> type.getDeclaredConstructor(String.class)
                .newInstance("request limit dependency");
        return Roots.start(RootsConfig.forApplication(Application.class)
                .port(0)
                .development(false)
                .maxRequestBytes(maxRequestBytes)
                .instanceFactory(factory)
                .build());
    }

    private static RunningApplication limitedApplication(
            int maxLiveViews,
            int maxSessions,
            int maxConcurrentRequests
    ) {
        InstanceFactory factory = type -> type.getDeclaredConstructor(String.class)
                .newInstance("limited dependency");
        return Roots.start(RootsConfig.forApplication(Application.class)
                .port(0)
                .development(false)
                .maxConcurrentRequests(maxConcurrentRequests)
                .maxLiveViews(maxLiveViews)
                .maxSessions(maxSessions)
                .mapException(com.chaplin.roots.testapp.FixtureProblem.class,
                        (request, failure) -> Response.text(500, failure.getMessage()))
                .instanceFactory(factory)
                .build());
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Condition was not met within " + timeout);
    }

    private static String attribute(Pattern pattern, String html) {
        var matcher = pattern.matcher(html);
        assertTrue(matcher.find(), () -> "Missing " + pattern.pattern() + " in " + html);
        return matcher.group(1);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpRequest clusterAction(URI base, BrowserView view) {
        return HttpRequest.newBuilder(base.resolve("_roots/action"))
                .header("Cookie", view.cookie())
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString("_view=" + encode(view.viewId())
                        + "&_csrf=" + encode(view.csrf())
                        + "&_action=" + encode(view.action())
                        + "&_event=click&_protocol=" + encode(Roots.PROTOCOL_VERSION)))
                .build();
    }

    private static class SharedOwnership implements LiveViewOwnership {
        private final String nodeId;
        private final Map<String, LiveViewOwner> leases;
        private final AtomicInteger renewals = new AtomicInteger();

        private SharedOwnership(String nodeId, Map<String, LiveViewOwner> leases) {
            this.nodeId = nodeId;
            this.leases = leases;
        }

        public String localNodeId() { return nodeId; }

        public boolean claim(String viewId, String sessionId, Instant now, Instant expiresAt) {
            var claimed = new LiveViewOwner(nodeId, sessionId, expiresAt);
            var result = leases.compute(viewId, (ignored, current) -> current == null
                    || !current.expiresAt().isAfter(now) ? claimed : current);
            return claimed.equals(result);
        }

        public boolean renew(String viewId, String sessionId, Instant now, Instant expiresAt) {
            var renewed = new LiveViewOwner(nodeId, sessionId, expiresAt);
            var result = leases.computeIfPresent(viewId, (ignored, current) ->
                    current.nodeId().equals(nodeId) && current.sessionId().equals(sessionId)
                            && current.expiresAt().isAfter(now) ? renewed : current);
            if (renewed.equals(result)) {
                renewals.incrementAndGet();
                return true;
            }
            return false;
        }

        public Optional<LiveViewOwner> find(String viewId, Instant now) {
            return Optional.ofNullable(leases.computeIfPresent(viewId,
                    (ignored, current) -> current.expiresAt().isAfter(now) ? current : null));
        }

        public void release(String viewId, String sessionId) {
            leases.computeIfPresent(viewId, (ignored, current) -> current.nodeId().equals(nodeId)
                    && current.sessionId().equals(sessionId) ? null : current);
        }

        public void deleteExpired(Instant now) {
            leases.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        }
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

    private static MultipartPart multipartPart(String headers, byte[] content) {
        return new MultipartPart(headers, content);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(first);
        output.writeBytes(second);
        return output.toByteArray();
    }

    private record BrowserView(String cookie, String viewId, String csrf, String action) {
    }

    private record LiveViewCredentials(String cookie, String viewId, String csrf) {
    }

    private record MultipartPart(String headers, byte[] content) {
    }
}
