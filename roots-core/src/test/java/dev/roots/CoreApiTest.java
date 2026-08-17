package dev.roots;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoreApiTest {
    @Test
    void apiRouteDispatchesEverySupportedMethodAndRejectsUnknownMethods() throws Exception {
        ApiRoute defaults = new ApiRoute() { };
        assertEquals(405, defaults.handle(request("GET")).status());
        assertEquals("Method not allowed: HEAD", defaults.handle(request("HEAD")).bodyText());
        assertEquals(405, defaults.handle(request("POST")).status());
        assertEquals(405, defaults.handle(request("PUT")).status());
        assertEquals(405, defaults.handle(request("PATCH")).status());
        assertEquals(405, defaults.handle(request("DELETE")).status());
        var defaultOptions = defaults.handle(request("OPTIONS"));
        assertEquals(204, defaultOptions.status());
        assertEquals("OPTIONS", defaultOptions.headers().get("Allow").getFirst());
        var defaultUnknown = defaults.handle(request("TRACE"));
        assertEquals(405, defaultUnknown.status());
        assertEquals("OPTIONS", defaultUnknown.headers().get("Allow").getFirst());

        ApiRoute custom = new ApiRoute() {
            @Override public Response get(Request request) { return Response.text(200, "get"); }
            @Override public Response post(Request request) { return Response.text(201, "post"); }
            @Override public Response put(Request request) { return Response.text(202, "put"); }
            @Override public Response patch(Request request) { return Response.text(203, "patch"); }
            @Override public Response delete(Request request) { return Response.noContent(); }
        };
        assertEquals("get", custom.handle(request("GET")).bodyText());
        assertEquals("get", custom.handle(request("HEAD")).bodyText());
        assertEquals("post", custom.handle(request("POST")).bodyText());
        assertEquals("put", custom.handle(request("PUT")).bodyText());
        assertEquals("patch", custom.handle(request("PATCH")).bodyText());
        assertEquals(204, custom.handle(request("DELETE")).status());
        var options = custom.handle(request("OPTIONS"));
        assertEquals(204, options.status());
        assertEquals("GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS",
                options.headers().get("Allow").getFirst());
        assertEquals(options.headers().get("Allow"),
                custom.handle(request("CONNECT")).headers().get("Allow"));

        ApiRoute headOnly = new ApiRoute() {
            @Override public Response head(Request request) { return Response.noContent(); }
            @Override public Response post(Request request) { return Response.noContent(); }
        };
        assertEquals("HEAD, POST, OPTIONS",
                headOnly.handle(request("GET")).headers().get("Allow").getFirst());
    }

    @Test
    void sessionSupportsTypedValuesRemovalAndSnapshots() {
        var session = new Session("session-1");
        session.put("count", 3);
        session.put("name", "Ada");

        assertEquals("session-1", session.id());
        assertEquals(3, session.get("count", Integer.class).orElseThrow());
        assertTrue(session.get("count", String.class).isEmpty());
        assertEquals(Map.of("count", 3, "name", "Ada"), session.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> session.snapshot().put("no", "no"));

        session.put("count", null);
        session.remove("name");
        assertTrue(session.snapshot().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Session(" "));
        assertThrows(NullPointerException.class, () -> session.get(null));
        assertThrows(NullPointerException.class, () -> session.put(null, "bad"));
    }

    @Test
    void stateMemoContextAndMetadataCoverTheirBoundaryBehavior() {
        var state = State.of("first");
        assertEquals("first", state.get());
        state.set("second");
        assertEquals("second!", state.update(value -> value + "!"));
        assertThrows(NullPointerException.class, () -> state.update(null));

        var memo = new Memo<String>();
        assertEquals("one", memo.compute(null, () -> "one"));
        memo.clear();
        assertEquals("two", memo.compute(null, () -> "two"));

        var required = ContextKey.<String>required("tenant");
        var trace = new TraceContext(
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "00f067aa0ba902b7",
                Optional.empty(),
                1
        );
        var context = new PageContext(
                "/customers/42",
                Map.of("customerId", "42"),
                Map.of("filter", List.of("active", "new")),
                new Session("context-session"),
                RootsCache.disabled(),
                Optional.empty(),
                trace
        );
        assertSame(trace, context.traceContext());
        assertEquals("", context.mountPath());
        assertEquals("/customers", context.url("/customers"));
        var mountedContext = new PageContext(
                "/", Map.of(), Map.of(), new Session("mounted-context"), RootsCache.disabled(),
                Optional.empty(), trace, "/company/roots",
                ClientConnection.direct(InetAddress.ofLiteral("203.0.113.4"), "https", "app.example")
        );
        assertEquals("203.0.113.4", mountedContext.connection().clientAddressText());
        mountedContext.updateConnection(ClientConnection.unknown("http", "localhost"));
        assertEquals("unknown", mountedContext.connection().clientAddressText());
        assertEquals("/company/roots/customers", mountedContext.url("/customers"));
        assertEquals("/company/roots/customers", mountedContext.url("/company/roots/customers"));
        assertEquals("https://example.com", mountedContext.url("https://example.com"));
        assertThrows(IllegalArgumentException.class, () -> new PageContext(
                "/", Map.of(), Map.of(), new Session("bad-mount"), RootsCache.disabled(),
                Optional.empty(), trace, "relative"
        ));
        assertEquals("42", context.parameter("customerId"));
        assertEquals(List.of("active", "new"), context.queryValues("filter"));
        assertEquals("active", context.query("filter").orElseThrow());
        assertTrue(context.query("missing").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> context.parameter("missing"));
        assertThrows(IllegalStateException.class, () -> context.context(required));
        assertTrue(required.toString().contains("tenant"));
        context.provide(required, "north");
        assertEquals("north", context.context(required));
        context.provide(required, null);
        assertThrows(IllegalStateException.class, () -> context.context(required));

        var metadata = new Metadata(" ", null, null);
        assertEquals("Roots", metadata.title());
        assertEquals("", metadata.description());
        assertTrue(metadata.stylesheets().isEmpty());
        assertTrue(metadata.fonts().isEmpty());
        assertEquals(List.of("/a.css", "/b.css"), Metadata.of("Title", "Description", "/a.css", "/b.css").stylesheets());

        var font = WebFont.of("Roots Sans", "/fonts/roots.woff2")
                .weight("100 900")
                .style("italic")
                .display("optional")
                .preloaded();
        assertEquals("Roots Sans", font.family());
        assertEquals("100 900", font.weight());
        assertTrue(font.preload());
        assertEquals(List.of(font), metadata.withFonts(font).fonts());
        assertThrows(IllegalArgumentException.class, () -> WebFont.of("Bad; family", "/font.woff2"));
        assertThrows(IllegalArgumentException.class, () -> WebFont.of("Good", "https://example.com/font.woff2"));
        assertThrows(IllegalArgumentException.class, () -> WebFont.of("Good", "/font.css"));
        assertThrows(IllegalArgumentException.class, () -> WebFont.of("Good", "/font.woff2").weight("950"));
        assertThrows(IllegalArgumentException.class, () -> WebFont.of("Good", "/font.woff2").style("slanted"));
        assertThrows(IllegalArgumentException.class, () -> WebFont.of("Good", "/font.woff2").display("forever"));

        var openGraph = new OpenGraphMetadata()
                .withType("website")
                .withUrl("https://example.com/customers")
                .withImage("/images/customers.png")
                .withImageAlt("Customer dashboard")
                .withSiteName("Roots Console");
        var head = new HeadMetadata()
                .withCanonical("/customers")
                .withRobots("index, follow")
                .withThemeColor("#14213d")
                .withOpenGraph(openGraph);
        assertEquals("/customers", head.canonical());
        assertEquals("website", head.openGraph().type());
        assertFalse(head.openGraph().isEmpty());
        assertTrue(OpenGraphMetadata.EMPTY.isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new HeadMetadata().withCanonical("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenGraphMetadata().withImage("//untrusted.example/image.png"));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenGraphMetadata().withUrl("customers"));
        assertThrows(IllegalArgumentException.class,
                () -> new HeadMetadata().withCanonical("/customers/bad path"));
    }

    @Test
    void pageContextRejectsDetachedOrRepeatedUpdaterAttachment() {
        var context = new PageContext("/", Map.of(), Map.of(), new Session("updater-session"));
        assertThrows(IllegalStateException.class, () -> context.update(() -> { }));
        assertEquals("/", context.cookiePath());
        assertTrue(context.cookies().isEmpty());
        context.updateCookies(Map.of("theme", "dark"));
        assertEquals("dark", context.cookie("theme").orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> context.cookies().put("no", "value"));
        assertThrows(IllegalArgumentException.class, () -> context.updateCookies(Map.of("bad name", "value")));
        var tooManyCookies = new LinkedHashMap<String, String>();
        for (var index = 0; index < 129; index++) tooManyCookies.put("cookie-" + index, "value");
        assertThrows(IllegalArgumentException.class, () -> context.updateCookies(tooManyCookies));

        context.attachUpdater(Runnable::run);
        var state = State.of(0);
        context.update(() -> state.set(1));
        assertEquals(1, state.get());
        assertThrows(IllegalStateException.class, () -> context.attachUpdater(Runnable::run));
    }

    @Test
    void actionEventValidatesValuesRedirectsAndEffects() {
        var ref = Ref.create();
        var event = new ActionEvent(
                "submit",
                Map.of("name", List.of("Ada"), "roles", List.of("admin", "reviewer"), "blank", List.of(" ")),
                new Session("event-session")
        );

        assertEquals("submit", event.type());
        assertEquals("Ada", event.required("name"));
        assertEquals(List.of("admin", "reviewer"), event.values("roles"));
        assertTrue(event.value("missing").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> event.required("blank"));
        assertThrows(UnsupportedOperationException.class, () -> event.allValues().put("no", List.of()));

        event.redirect("/customers");
        event.focus(ref);
        event.blur(ref);
        event.selectText(ref);
        event.scrollIntoView(ref);
        event.copyToClipboard("customer-42");
        event.announce("Customer saved");
        event.announceAssertively("Session expires soon");
        event.viewTransition();
        event.viewTransition();
        assertEquals("/customers", event.redirect().orElseThrow());
        assertEquals(8, event.effects().size());
        assertEquals(ClientEffect.Type.VIEW_TRANSITION, event.effects().getLast().type());
        assertThrows(IllegalArgumentException.class, () -> event.redirect("https://example.com"));
        assertThrows(IllegalArgumentException.class, () -> event.redirect("//example.com"));
        assertThrows(IllegalArgumentException.class, () -> event.redirect(null));
        assertTrue(event.traceContext().traceparent().startsWith("00-"));
        var mountedEvent = new ActionEvent(
                BrowserEvent.empty(BrowserEvent.Type.CLICK),
                Map.of(),
                Map.of(),
                new Session("mounted-event"),
                RootsCache.disabled(),
                Optional.empty(),
                TraceContext.create(),
                "/company/roots",
                ClientConnection.direct(InetAddress.ofLiteral("198.51.100.5"), "https", "app.example"),
                Map.of("theme", "dark")
        );
        assertEquals("/company/roots", mountedEvent.mountPath());
        assertEquals("/company/roots", mountedEvent.cookiePath());
        assertEquals("/company/roots/reports", mountedEvent.url("/reports"));
        assertEquals("198.51.100.5", mountedEvent.connection().clientAddressText());
        assertEquals("dark", mountedEvent.cookie("theme").orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> mountedEvent.cookies().put("no", "value"));
        mountedEvent.setCookie(ResponseCookie.builder("preference", "wide")
                .path(mountedEvent.cookiePath()).build());
        mountedEvent.deleteCookie("obsolete");
        assertEquals(2, mountedEvent.responseCookies().size());
        assertTrue(mountedEvent.responseCookies().getLast().headerValue().contains("Path=/company/roots"));
        assertTrue(mountedEvent.responseCookies().getLast().secure());

        assertEquals(ClientEffect.viewTransition(),
                new ClientEffect(ClientEffect.Type.VIEW_TRANSITION, null, null));
        assertEquals(ClientEffect.blur(ref),
                new ClientEffect(ClientEffect.Type.BLUR, ref.id(), null));
        assertEquals(ClientEffect.selectText(ref),
                new ClientEffect(ClientEffect.Type.SELECT_TEXT, ref.id(), null));
        assertEquals(ClientEffect.announce("Saved"),
                new ClientEffect(ClientEffect.Type.ANNOUNCE_POLITE, null, "Saved"));
        assertEquals(ClientEffect.announceAssertively("Warning"),
                new ClientEffect(ClientEffect.Type.ANNOUNCE_ASSERTIVE, null, "Warning"));
        assertThrows(NullPointerException.class, () -> new ClientEffect(null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientEffect(ClientEffect.Type.FOCUS, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientEffect(ClientEffect.Type.BLUR, ref.id(), "value"));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientEffect(ClientEffect.Type.SELECT_TEXT, "bad ref", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientEffect(ClientEffect.Type.SCROLL_INTO_VIEW, ref.id(), "value"));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientEffect(ClientEffect.Type.COPY_TO_CLIPBOARD, ref.id(), "value"));
        assertThrows(NullPointerException.class, () -> ClientEffect.copyToClipboard(null));
        assertThrows(IllegalArgumentException.class,
                () -> ClientEffect.copyToClipboard("x".repeat(16_385)));
        assertThrows(NullPointerException.class, () -> ClientEffect.announce(null));
        assertThrows(IllegalArgumentException.class, () -> ClientEffect.announce("  "));
        assertThrows(IllegalArgumentException.class,
                () -> ClientEffect.announceAssertively("x".repeat(2_049)));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientEffect(ClientEffect.Type.ANNOUNCE_POLITE, ref.id(), "Saved"));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientEffect(ClientEffect.Type.VIEW_TRANSITION, ref.id(), null));

        var bounded = new ActionEvent("click", Map.of(), new Session("bounded-effects"));
        for (var index = 0; index < 32; index++) {
            bounded.copyToClipboard("effect-" + index);
        }
        assertThrows(IllegalStateException.class, () -> bounded.focus(ref));

        var boundedCookies = new ActionEvent("click", Map.of(), new Session("bounded-cookies"));
        for (var index = 0; index < 32; index++) {
            boundedCookies.setCookie(ResponseCookie.builder("cookie-" + index, "value").build());
        }
        assertThrows(IllegalStateException.class,
                () -> boundedCookies.setCookie(ResponseCookie.builder("overflow", "value").build()));
        assertThrows(UnsupportedOperationException.class,
                () -> boundedCookies.responseCookies().add(ResponseCookie.builder("no", "value").build()));
        assertThrows(NullPointerException.class,
                () -> new ActionEvent("click", Map.of(), new Session("null-cookie")).setCookie(null));
    }

    @Test
    void responseFactoriesProduceExpectedStatusHeadersAndBodies() {
        assertEquals("plain", Response.text(200, "plain").bodyText());
        assertTrue(Response.html(200, "<p>ok</p>").headers().get("Content-Type").getFirst().startsWith("text/html"));
        assertTrue(Response.json(200, "{}").headers().get("Content-Type").getFirst().startsWith("application/json"));
        assertEquals(204, Response.noContent().status());
        assertEquals("/next", Response.redirect("/next").headers().get("Location").getFirst());
        assertEquals(405, Response.methodNotAllowed("TRACE").status());
        assertEquals("body", Response.of(202, "application/custom", "body").bodyText());
    }

    @Test
    void requestMountPathsAreValidatedAndPreservedByLogicalCopies() {
        var original = request("GET");
        var mounted = new Request(
                original.method(), original.path(), original.transportPath(), original.parameters(),
                original.query(), original.headers(), original.form(), original.files(), original.body(),
                original.session(), original.cache(), original.identity(), original.traceContext(), "/company/roots",
                ClientConnection.direct(InetAddress.ofLiteral("192.0.2.8"), "https", "app.example")
        );

        assertEquals("/company/roots", mounted.mountPath());
        assertEquals("/company/roots", mounted.withLogicalPath("/next").mountPath());
        assertEquals("/company/roots", mounted.withParameters(Map.of("id", "1")).mountPath());
        assertEquals("192.0.2.8", mounted.withLogicalPath("/next").connection().clientAddressText());
        assertThrows(IllegalArgumentException.class, () -> new Request(
                original.method(), original.path(), original.transportPath(), original.parameters(),
                original.query(), original.headers(), original.form(), original.files(), original.body(),
                original.session(), original.cache(), original.identity(), original.traceContext(), "/bad; Path=/"
        ));
    }

    @Test
    void configParsesAllCommandLineOptionsAndLegacyConstructor() {
        RequestObserver observer = ignored -> { };
        var config = RootsConfig.forApplication(CoreApiTest.class)
                .pagesPackage("example.pages")
                .apiPackage("example.api")
                .observeRequests(observer)
                .arguments("--port=0", "--host=0.0.0.0", "--production", "")
                .build();
        assertEquals(0, config.port());
        assertEquals("0.0.0.0", config.host());
        assertFalse(config.development());
        assertEquals("example.pages", config.pagesPackage());
        assertEquals("example.api", config.apiPackage());
        assertEquals(20_000, config.maxConcurrentRequests());
        assertEquals(10_000, config.maxLiveViews());
        assertEquals(100_000, config.maxSessions());
        assertTrue(config.cache().snapshot().enabled());
        assertEquals(RootsCache.DEFAULT_MAX_ENTRIES, config.cache().snapshot().maxEntries());
        assertEquals(RootsConfig.DEFAULT_CONTENT_SECURITY_POLICY, config.contentSecurityPolicy());
        assertEquals(List.of(observer), config.requestObservers());
        assertThrows(UnsupportedOperationException.class, () -> config.requestObservers().add(observer));

        var repository = SessionRepository.inMemory();
        AuthenticationProvider authenticationProvider = request -> request.identity();
        var customPolicy = RootsConfig.forApplication(CoreApiTest.class)
                .contentSecurityPolicy("  default-src 'none'  ")
                .sessionRepository(repository)
                .authenticationProvider(authenticationProvider)
                .build();
        assertEquals("default-src 'none'", customPolicy.contentSecurityPolicy());
        assertSame(repository, customPolicy.sessionRepository());
        assertSame(authenticationProvider, customPolicy.authenticationProvider());

        var legacy = new RootsConfig(
                CoreApiTest.class,
                "example.pages",
                "example.api",
                "localhost",
                8080,
                true,
                Duration.ofMinutes(1)
        );
        assertEquals(Duration.ofHours(8), legacy.sessionTimeout());
        assertFalse(legacy.secureCookies());
        assertTrue(legacy.middleware().isEmpty());
        assertTrue(legacy.cache().snapshot().enabled());
        var preCspCanonical = new RootsConfig(
                CoreApiTest.class,
                "example.pages",
                "example.api",
                "localhost",
                8080,
                true,
                Duration.ofMinutes(1),
                Duration.ofHours(1),
                RootsConfig.DEFAULT_MAX_REQUEST_BYTES,
                20,
                10,
                100,
                false,
                InstanceFactory.reflection(),
                Map.of(),
                List.of(),
                List.of(),
                RootsCache.disabled()
        );
        assertEquals(RootsConfig.DEFAULT_CONTENT_SECURITY_POLICY, preCspCanonical.contentSecurityPolicy());
        var legacySnapshot = new RuntimeSnapshot(true, 12, 3, 4);
        assertTrue(legacySnapshot.acceptingRequests());
        assertEquals(Integer.MAX_VALUE, legacySnapshot.maxLiveViews());
        assertEquals(Integer.MAX_VALUE, legacySnapshot.maxConcurrentRequests());
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(CoreApiTest.class).port(65_536).build());
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(CoreApiTest.class).host(" ").build());
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .viewTimeout(Duration.ofNanos(1)).build());
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .sessionTimeout(Duration.ofNanos(1)).build());
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .maxConcurrentRequests(0).build());
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .maxConcurrentRequests(10_000).maxLiveViews(10_000).build());
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .maxLiveViews(0).build());
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .maxSessions(-1).build());
        assertThrows(NullPointerException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .cache(null));
        assertThrows(NullPointerException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .sessionRepository(null));
        assertThrows(NullPointerException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .authenticationProvider(null));
        assertThrows(NullPointerException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .observeRequests(null));
        assertThrows(NullPointerException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .contentSecurityPolicy(null));
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .contentSecurityPolicy(" "));
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .contentSecurityPolicy("default-src 'self'\r\nX-Evil: true"));
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(CoreApiTest.class)
                .contentSecurityPolicy("x".repeat(8_193)));
    }

    @Test
    void typedExceptionMappersAndValidationErrorsAreImmutable() {
        var errors = new ArrayList<>(List.of("Required"));
        var source = new LinkedHashMap<String, List<String>>();
        source.put("name", errors);
        var validation = new ValidationException("Invalid customer", source);
        errors.add("mutated");

        assertEquals("Invalid customer", validation.getMessage());
        assertEquals(List.of("Required"), validation.fieldErrors().get("name"));
        assertThrows(UnsupportedOperationException.class, () -> validation.fieldErrors().get("name").add("no"));
        assertEquals("Validation failed", ValidationException.field("email", "Invalid").getMessage());
        assertThrows(IllegalArgumentException.class, () -> new ValidationException(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ValidationException.field(" ", "Invalid"));
        assertThrows(IllegalArgumentException.class, () -> ValidationException.field("name", " "));
        assertThrows(IllegalArgumentException.class,
                () -> ValidationException.field("x".repeat(129), "Invalid"));
        assertThrows(IllegalArgumentException.class,
                () -> ValidationException.field("bad\nfield", "Invalid"));
        assertThrows(IllegalArgumentException.class,
                () -> ValidationException.field("name", "x".repeat(2_049)));
        assertThrows(IllegalArgumentException.class,
                () -> new ValidationException("x".repeat(2_049), Map.of("name", List.of("Invalid"))));
        assertThrows(IllegalArgumentException.class,
                () -> new ValidationException(Map.of("name", java.util.Collections.nCopies(17, "Invalid"))));
        var tooManyFields = new LinkedHashMap<String, List<String>>();
        for (var index = 0; index < 129; index++) {
            tooManyFields.put("field" + index, List.of("Invalid"));
        }
        assertThrows(IllegalArgumentException.class, () -> new ValidationException(tooManyFields));
        var tooManyMessages = new LinkedHashMap<String, List<String>>();
        for (var index = 0; index < 17; index++) {
            tooManyMessages.put("field" + index, java.util.Collections.nCopies(16, "Invalid"));
        }
        assertThrows(IllegalArgumentException.class, () -> new ValidationException(tooManyMessages));

        var request = request("GET");
        var mapper = ExceptionMapper.forType(
                IllegalStateException.class,
                (ignored, failure) -> Response.text(409, failure.getMessage())
        );
        assertTrue(mapper.map(request, new IllegalArgumentException("wrong type")).isEmpty());
        assertEquals(409, mapper.map(request, new IllegalStateException("mapped")).orElseThrow().status());

        var config = RootsConfig.forApplication(CoreApiTest.class)
                .mapExceptions(mapper)
                .mapException(IllegalArgumentException.class, (ignored, failure) -> Response.text(400, failure.getMessage()))
                .build();
        assertEquals(2, config.exceptionMappers().size());
        assertThrows(UnsupportedOperationException.class, () -> config.exceptionMappers().add(mapper));
    }

    @Test
    void prerenderValueTypesValidateAndDefensivelyCopyTheirState() {
        var output = Path.of("target", "generated-static");
        var routes = new ArrayList<>(List.of(new PrerenderedRoute("/about", "static/about.html", 60)));
        var report = new PrerenderReport(output, routes);
        routes.clear();

        assertEquals(output.toAbsolutePath().normalize(), report.outputDirectory());
        assertEquals(1, report.routes().size());
        assertThrows(UnsupportedOperationException.class,
                () -> report.routes().add(new PrerenderedRoute("/", "index.html", 0)));
        assertThrows(IllegalArgumentException.class, () -> new PrerenderedRoute("relative", "x", 0));
        assertThrows(IllegalArgumentException.class, () -> new PrerenderedRoute("/", " ", 0));
        assertThrows(IllegalArgumentException.class, () -> new PrerenderedRoute("/", "x", -1));
        assertThrows(NullPointerException.class, () -> new PrerenderReport(null, List.of()));
    }

    private static Request request(String method) {
        return new Request(
                method,
                "/",
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                new byte[0],
                new Session("api-" + method)
        );
    }
}
