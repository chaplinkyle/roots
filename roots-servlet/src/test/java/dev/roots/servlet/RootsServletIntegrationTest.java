package dev.roots.servlet;

import dev.roots.AuthenticatedIdentity;
import dev.roots.RequestObservation;
import dev.roots.Roots;
import dev.roots.RootsConfig;
import dev.roots.ProxyPolicy;
import dev.roots.RateLimiter;
import dev.roots.servlet.fixture.Application;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.lang.reflect.Proxy;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RootsServletIntegrationTest {
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");
    private static final Pattern ACTION = Pattern.compile("data-roots-on-click=\"([^\"]+:increment)\"");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final CopyOnWriteArrayList<RequestObservation> OBSERVATIONS = new CopyOnWriteArrayList<>();
    private static Tomcat tomcat;
    private static RootsServlet servlet;
    private static RootsServlet shutdownServlet;
    private static RootsServlet networkServlet;
    private static URI base;

    @BeforeAll
    static void start() throws Exception {
        var baseDirectory = Files.createTempDirectory("roots-tomcat-");
        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDirectory.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        var context = tomcat.addContext("/company", baseDirectory.toString());
        servlet = new RootsServlet(RootsConfig.forApplication(Application.class)
                .development(false)
                .observeRequests(OBSERVATIONS::add)
                .build(), Duration.ofSeconds(3));
        var wrapper = Tomcat.addServlet(context, "roots", servlet);
        wrapper.setAsyncSupported(true);
        context.addServletMappingDecoded("/*", "roots");

        var identity = Tomcat.addServlet(context, "identity", new RootsServlet(
                RootsConfig.forApplication(Application.class).development(false).build(),
                Duration.ofSeconds(3),
                request -> Optional.ofNullable(request.getHeader("X-Identity"))
                        .map(name -> AuthenticatedIdentity.of(name, Set.of("ROLE_ADMIN")))
        ));
        identity.setAsyncSupported(true);
        context.addServletMappingDecoded("/identity/*", "identity");

        var descriptor = Tomcat.addServlet(context, "descriptor", RootsServlet.class.getName());
        descriptor.addInitParameter("roots.applicationClass", Application.class.getName());
        descriptor.addInitParameter("roots.development", "false");
        descriptor.setAsyncSupported(true);
        context.addServletMappingDecoded("/descriptor/*", "descriptor");

        var prerender = Tomcat.addServlet(context, "prerender", new RootsServlet(
                RootsConfig.forApplication(dev.roots.servlet.prerender.Application.class)
                        .development(false)
                        .build(),
                Duration.ofSeconds(3)
        ));
        prerender.setAsyncSupported(true);
        context.addServletMappingDecoded("/static/*", "prerender");

        networkServlet = new RootsServlet(
                RootsConfig.forApplication(Application.class)
                        .development(false)
                        .proxyPolicy(ProxyPolicy.trusted("127.0.0.0/8"))
                        .rateLimiter(RateLimiter.fixedWindow(2, Duration.ofMinutes(1), 10))
                        .build(),
                Duration.ofSeconds(3)
        );
        var network = Tomcat.addServlet(context, "network", networkServlet);
        network.setAsyncSupported(true);
        context.addServletMappingDecoded("/network/*", "network");

        var synchronous = Tomcat.addServlet(context, "synchronous", new RootsServlet(
                RootsConfig.forApplication(Application.class).build()));
        synchronous.setAsyncSupported(false);
        context.addServletMappingDecoded("/synchronous/*", "synchronous");

        shutdownServlet = new RootsServlet(
                RootsConfig.forApplication(Application.class).build(), Duration.ofMillis(100));
        var shutdown = Tomcat.addServlet(context, "shutdown", shutdownServlet);
        shutdown.setAsyncSupported(true);
        context.addServletMappingDecoded("/shutdown/*", "shutdown");
        tomcat.start();
        base = URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/company/");
    }

    @AfterAll
    static void stop() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @Test
    void servesPagesApisAssetsAndHealthBelowAContextPath() throws Exception {
        var health = get("_roots/health");
        assertEquals(200, health.statusCode(), health.body());
        assertEquals("{\"status\":\"UP\",\"ready\":true,\"node\":\"" + servlet.nodeId() + "\"}", health.body());
        assertTrue(health.headers().firstValue("Set-Cookie").isEmpty());

        var asset = get("servlet.css");
        assertEquals(200, asset.statusCode());
        assertTrue(asset.body().contains("#123456"));
        assertTrue(asset.headers().firstValue("Set-Cookie").isEmpty());

        var page = get("");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Servlet transport"), page.body());
        assertTrue(page.body().contains("Mount /company"), page.body());
        assertTrue(page.body().contains("src=\"/company/_roots/client.js\""), page.body());
        assertTrue(page.body().contains("data-roots-framework-style href=\"/company/_roots/client.css\""), page.body());
        assertTrue(page.body().contains("data-roots-announcer=\"polite\""), page.body());
        assertTrue(page.body().contains("href=\"/company/\""), page.body());
        assertTrue(page.headers().firstValue("Set-Cookie").orElseThrow().contains("Path=/company;"));
        assertTrue(page.headers().firstValue("Content-Security-Policy").isPresent());
        var frameworkCss = get("_roots/client.css");
        assertEquals(200, frameworkCss.statusCode());
        assertTrue(frameworkCss.body().contains("[data-roots-announcer]"));
        assertTrue(frameworkCss.headers().firstValue("Set-Cookie").isEmpty());
        var cookie = cookie(page);

        var api = CLIENT.send(HttpRequest.newBuilder(base.resolve("api/echo?value=two"))
                .header("Cookie", cookie)
                .header("X-Fixture", "servlet")
                .POST(HttpRequest.BodyPublishers.ofString("hello"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, api.statusCode());
        assertEquals("{\"body\":\"hello\",\"query\":\"two\",\"header\":\"servlet\"}", api.body());
        assertEquals("nosniff", api.headers().firstValue("X-Content-Type-Options").orElseThrow());

        var missing = get("missing-page");
        assertEquals(404, missing.statusCode());
        assertTrue(missing.body().contains("Servlet custom 404"), missing.body());
        assertTrue(missing.body().contains("href=\"/company/\""), missing.body());
        assertTrue(missing.body().contains("src=\"/company/_roots/client.js\""), missing.body());
        assertTrue(missing.headers().firstValue("Set-Cookie").orElseThrow().contains("Path=/company;"));

        var failedPage = get("broken");
        assertEquals(500, failedPage.statusCode());
        assertTrue(failedPage.body().contains("Servlet custom 500"), failedPage.body());
        assertTrue(failedPage.body().contains("src=\"/company/_roots/client.js\""), failedPage.body());
        assertTrue(failedPage.headers().firstValue("Set-Cookie").orElseThrow().contains("Path=/company;"));
    }

    @Test
    void streamsResponsesAndHeadMetadataThroughServletIo() throws Exception {
        OBSERVATIONS.clear();
        dev.roots.servlet.fixture.api.stream.Route.reset();

        var streamed = CLIENT.send(HttpRequest.newBuilder(base.resolve("api/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, streamed.statusCode());
        assertEquals("servlet-stream-content", streamed.body());
        assertEquals(Integer.toString(dev.roots.servlet.fixture.api.stream.Route.length()),
                streamed.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(1, dev.roots.servlet.fixture.api.stream.Route.writes());
        var observation = OBSERVATIONS.stream()
                .filter(candidate -> candidate.path().equals("/api/stream") && candidate.responseBytes() > 0)
                .findFirst().orElseThrow();
        assertEquals(dev.roots.servlet.fixture.api.stream.Route.length(), observation.responseBytes());

        var head = CLIENT.send(HttpRequest.newBuilder(base.resolve("api/stream"))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
        assertEquals(Integer.toString(dev.roots.servlet.fixture.api.stream.Route.length()),
                head.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(1, dev.roots.servlet.fixture.api.stream.Route.writes());

        var options = CLIENT.send(HttpRequest.newBuilder(base.resolve("api/stream"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(204, options.statusCode());
        assertEquals(0, options.body().length);
        assertEquals("GET, HEAD, OPTIONS", options.headers().firstValue("Allow").orElseThrow());
        assertEquals(1, dev.roots.servlet.fixture.api.stream.Route.writes());

        var unsupported = CLIENT.send(HttpRequest.newBuilder(base.resolve("api/stream"))
                        .method("BREW", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, unsupported.statusCode());
        assertEquals("GET, HEAD, OPTIONS", unsupported.headers().firstValue("Allow").orElseThrow());
    }

    @Test
    void spoolsAndCleansLargeMultipartBodiesOnTheServletTransport() throws Exception {
        var boundary = "servlet-spool-boundary";
        var binary = new byte[100_000];
        for (var index = 0; index < binary.length; index++) {
            binary[index] = (byte) (index * 13);
        }
        var output = new ByteArrayOutputStream();
        output.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"attachment\"; filename=\"large.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(binary);
        output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));

        var response = CLIENT.send(HttpRequest.newBuilder(base.resolve("api/echo"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray()))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"requestInMemory\":false"), response.body());
        assertTrue(response.body().contains("\"uploadInMemory\":false"), response.body());
        assertTrue(response.body().contains("\"bytes\":100000"), response.body());
        assertThrows(java.io.IOException.class,
                () -> dev.roots.servlet.fixture.api.echo.Route.lastUpload().openStream());
    }

    @Test
    void propagatesTraceContextAndObservesServletResponses() throws Exception {
        var traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        var response = CLIENT.send(HttpRequest.newBuilder(base.resolve("api/echo?value=trace"))
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .header("X-Fixture", "servlet")
                .POST(HttpRequest.BodyPublishers.ofString("observed"))
                .build(), HttpResponse.BodyHandlers.ofString());
        var traceparent = response.headers().firstValue("traceparent").orElseThrow();
        var spanId = traceparent.split("-", -1)[2];

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(traceId, traceparent.split("-", -1)[1]);
        assertFalse(spanId.equals("00f067aa0ba902b7"));
        var observation = OBSERVATIONS.stream()
                .filter(candidate -> candidate.traceContext().spanId().equals(spanId))
                .findFirst().orElseThrow();
        assertEquals("/api/echo", observation.path());
        assertEquals("/api/echo", observation.transportPath());
        assertEquals(200, observation.status());
        assertEquals(response.body().getBytes(StandardCharsets.UTF_8).length, observation.responseBytes());
    }

    @Test
    void resolvesTrustedProxyDetailsAcrossMountedPagesAndActionsBeforeRateLimiting() throws Exception {
        var forwarded = "for=203.0.113.61;proto=https;host=public.example";
        var page = CLIENT.send(HttpRequest.newBuilder(base.resolve("network/"))
                .header("Forwarded", forwarded)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.body().contains("Mount /company/network"), page.body());
        assertTrue(page.body().contains("Client 203.0.113.61"), page.body());
        assertTrue(page.body().contains("Origin https://public.example"), page.body());
        assertTrue(page.body().contains("Forwarded true"), page.body());

        var actionBody = "_view=" + encode(match(VIEW, page.body()))
                + "&_csrf=" + encode(match(CSRF, page.body()))
                + "&_protocol=" + encode(Roots.PROTOCOL_VERSION)
                + "&_action=" + encode(match(ACTION, page.body())) + "&_event=click";
        var action = CLIENT.send(HttpRequest.newBuilder(base.resolve("network/_roots/action"))
                .header("Forwarded", forwarded)
                .header("Cookie", cookie(page))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(actionBody)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, action.statusCode(), action.body());
        assertTrue(action.body().contains("Action client 203.0.113.61"), action.body());

        var rejected = CLIENT.send(HttpRequest.newBuilder(base.resolve("network/"))
                .header("Forwarded", forwarded)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(429, rejected.statusCode(), rejected.body());
        assertTrue(rejected.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(1, networkServlet.runtimeSnapshot().sessions());
    }

    @Test
    void rebasesPrerenderedHtmlAndValidatorsBelowAMountPath() throws Exception {
        var page = get("static/");

        assertEquals(200, page.statusCode(), page.body());
        assertEquals("HIT", page.headers().firstValue("X-Roots-Prerender").orElseThrow());
        assertTrue(page.headers().firstValue("Set-Cookie").isEmpty());
        assertTrue(page.body().contains("href=\"/company/static/servlet.css\""), page.body());
        assertTrue(page.body().contains("href=\"/company/static/\""), page.body());
        var etag = page.headers().firstValue("ETag").orElseThrow();
        var unchanged = CLIENT.send(HttpRequest.newBuilder(base.resolve("static/"))
                .header("If-None-Match", etag)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(304, unchanged.statusCode(), unchanged.body());
        assertEquals("", unchanged.body());
    }

    @Test
    void streamsOnAContainerAsyncContextAndExecutesLiveActions() throws Exception {
        var page = get("");
        var cookie = cookie(page);
        var view = match(VIEW, page.body());
        var csrf = match(CSRF, page.body());
        var action = match(ACTION, page.body());
        var stream = CLIENT.send(HttpRequest.newBuilder(base.resolve("_roots/stream?view="
                        + encode(view) + "&csrf=" + encode(csrf)
                        + "&protocol=" + encode(Roots.PROTOCOL_VERSION)))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());
        assertEquals("text/event-stream;charset=utf-8",
                stream.headers().firstValue("Content-Type").orElseThrow().replace(" ", ""));
        var reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));
        assertEquals(": connected", reader.readLine());
        assertEquals("", reader.readLine());

        var body = "_view=" + encode(view) + "&_csrf=" + encode(csrf)
                + "&_protocol=" + encode(Roots.PROTOCOL_VERSION)
                + "&_action=" + encode(action) + "&_event=click";
        var result = CLIENT.send(HttpRequest.newBuilder(base.resolve("_roots/action"))
                .header("Cookie", cookie + "; servlet-preference=compact")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertTrue(result.body().contains("Count 1"), result.body());
        assertTrue(result.body().contains("Render cookie compact"), result.body());
        assertTrue(result.body().contains("Action cookie compact"), result.body());
        assertEquals("servlet-result=seen-compact; Path=/company; SameSite=Lax",
                result.headers().firstValue("Set-Cookie").orElseThrow());
        assertTrue(servlet.runtimeSnapshot().activeRequests() >= 1);
        stream.body().close();
    }

    @Test
    void validatesConstructionAndInitializationState() {
        assertThrows(IllegalArgumentException.class,
                () -> new RootsServlet(RootsConfig.forApplication(Application.class).build(), Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class, () -> new RootsServlet(
                RootsConfig.forApplication(Application.class).build(), Duration.ZERO, null));
        var uninitialized = new RootsServlet(RootsConfig.forApplication(Application.class).build());
        assertThrows(IllegalStateException.class, uninitialized::runtimeSnapshot);
        assertThrows(IllegalStateException.class, uninitialized::routes);
        assertThrows(IllegalStateException.class, uninitialized::cache);
        uninitialized.destroy();
    }

    @Test
    void derivesNormalizedDirectConnectionsForDefaultPortsAndIpv6() {
        var http = transportConnection("192.0.2.10", "http", "app.example", 80);
        assertEquals("http://app.example", http.origin().toString());
        assertEquals("192.0.2.10", http.clientAddressText());
        assertFalse(http.forwarded());

        var httpsIpv6 = transportConnection("2001:db8::10", "https", "2001:db8::20", 443);
        assertEquals("https://[2001:db8::20]", httpsIpv6.origin().toString());

        var customPort = transportConnection("2001:db8::10", "https", "2001:db8::20", 8443);
        assertEquals("https://[2001:db8::20]:8443", customPort.origin().toString());
    }

    @Test
    void customResolverPropagatesAnImmutableTransportIdentity() throws Exception {
        var anonymous = CLIENT.send(HttpRequest.newBuilder(base.resolve("identity/"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        var authenticated = CLIENT.send(HttpRequest.newBuilder(base.resolve("identity/"))
                .header("X-Identity", "container-user")
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, anonymous.statusCode(), anonymous.body());
        assertTrue(anonymous.body().contains("Identity anonymous"), anonymous.body());
        assertEquals(200, authenticated.statusCode(), authenticated.body());
        assertTrue(authenticated.body().contains("Identity container-user"), authenticated.body());
    }

    @Test
    void productionDoesNotExposeDevelopmentProtocol() throws Exception {
        var response = get("_roots/development?since=0");
        assertEquals(404, response.statusCode());
        assertFalse(response.body().contains("compile-error"));
    }

    @Test
    void initializesFromDescriptorParametersAndRejectsSynchronousSse() throws Exception {
        var descriptorHealth = CLIENT.send(HttpRequest.newBuilder(base.resolve("descriptor/_roots/health"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, descriptorHealth.statusCode(), descriptorHealth.body());

        var page = CLIENT.send(HttpRequest.newBuilder(base.resolve("synchronous/"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        var response = CLIENT.send(HttpRequest.newBuilder(base.resolve("synchronous/_roots/stream?view=missing"))
                .header("Cookie", cookie(page)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("async-supported"), response.body());
    }

    @Test
    void destroyTerminatesAnAsyncStreamAndMakesTheServletUnavailable() throws Exception {
        var page = CLIENT.send(HttpRequest.newBuilder(base.resolve("shutdown/"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        var stream = CLIENT.send(HttpRequest.newBuilder(base.resolve("shutdown/_roots/stream?view="
                        + encode(match(VIEW, page.body())) + "&csrf=" + encode(match(CSRF, page.body()))
                        + "&protocol=" + encode(Roots.PROTOCOL_VERSION)))
                .header("Cookie", cookie(page)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        var reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));
        assertEquals(": connected", reader.readLine());
        assertTrue(shutdownServlet.runtimeSnapshot().activeRequests() >= 1);

        shutdownServlet.destroy();

        assertFalse(shutdownServlet.runtimeSnapshot().running());
        assertFalse(shutdownServlet.runtimeSnapshot().acceptingRequests());
        assertTrue(shutdownServlet.runtimeSnapshot().handledRequests() >= 2);
        var unavailable = CLIENT.send(HttpRequest.newBuilder(base.resolve("shutdown/_roots/health"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, unavailable.statusCode());
        stream.body().close();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookie(HttpResponse<?> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private static String match(Pattern pattern, String body) {
        var matcher = pattern.matcher(body);
        assertTrue(matcher.find(), body);
        return matcher.group(1);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static dev.roots.ClientConnection transportConnection(
            String remoteAddress,
            String scheme,
            String serverName,
            int serverPort
    ) {
        var request = (HttpServletRequest) Proxy.newProxyInstance(
                RootsServletIntegrationTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getRemoteAddr" -> remoteAddress;
                    case "getScheme" -> scheme;
                    case "getServerName" -> serverName;
                    case "getServerPort" -> serverPort;
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
        var response = (HttpServletResponse) Proxy.newProxyInstance(
                RootsServletIntegrationTest.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                }
        );
        return new ServletTransportExchange(request, response, null, Optional.empty()).connection();
    }
}
