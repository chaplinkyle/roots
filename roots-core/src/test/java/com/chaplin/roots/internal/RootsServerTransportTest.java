package com.chaplin.roots.internal;

import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.RequestObservation;
import com.chaplin.roots.ClientConnection;
import com.chaplin.roots.ProxyPolicy;
import com.chaplin.roots.RateLimitDecision;
import com.chaplin.roots.RateLimiter;
import com.chaplin.roots.InstanceFactory;
import com.chaplin.roots.Response;
import com.chaplin.roots.testapp.Application;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.InetAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RootsServerTransportTest {
    @Test
    void framesBufferedFixedChunkedHeadAndNoBodyResponsesAtTheSharedBoundary() throws Exception {
        var writes = new AtomicInteger();
        var fixedResponse = Response.stream(200, "application/octet-stream", 5, output -> {
            writes.incrementAndGet();
            output.write("roots".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        var fixed = exchange("GET", "/api/export");
        HttpSupport.send(fixed, fixedResponse, false);
        assertEquals(200, fixed.status);
        assertEquals(5, fixed.length);
        assertEquals("roots", fixed.body());
        assertEquals(1, writes.get());

        var head = exchange("HEAD", "/api/export");
        HttpSupport.send(head, fixedResponse, true);
        assertEquals(-1, head.length);
        assertEquals(List.of("5"), head.responseHeaders.get("Content-Length"));
        assertEquals("", head.body());
        assertEquals(1, writes.get());

        var chunked = exchange("GET", "/api/events");
        HttpSupport.send(chunked, Response.stream(200, "text/plain", output -> output.write('x')), false);
        assertEquals(0, chunked.length);
        assertEquals("x", chunked.body());

        var emptyWrites = new AtomicInteger();
        var empty = exchange("GET", "/api/empty");
        HttpSupport.send(empty, Response.stream(200, "text/plain", 0, output -> emptyWrites.incrementAndGet()), false);
        assertEquals(-1, empty.length);
        assertEquals(List.of("0"), empty.responseHeaders.get("Content-Length"));
        assertEquals("", empty.body());
        assertEquals(0, emptyWrites.get());

        var reset = exchange("GET", "/api/reset");
        HttpSupport.send(reset, Response.text(205, "must not be sent"), false);
        assertEquals(205, reset.status);
        assertEquals(-1, reset.length);
        assertEquals("", reset.body());
    }

    @Test
    void transportLifecycleIsIndependentOfTheEmbeddedListener() {
        var server = new RootsServer(RootsConfig.forApplication(Application.class).build());

        var stopped = exchange("GET", "/_roots/health");
        server.handleTransport(stopped);
        assertEquals(503, stopped.status);
        assertTrue(stopped.body().contains("not running"));

        server.startRuntime();
        assertTrue(server.runtimeSnapshot().running());
        assertTrue(server.runtimeSnapshot().acceptingRequests());
        assertThrows(IllegalStateException.class, server::startRuntime);

        var ready = exchange("HEAD", "/_roots/health");
        server.handleTransport(ready);
        assertEquals(200, ready.status);
        assertEquals(-1, ready.length);
        assertEquals("", ready.body());

        server.beginDrain();
        var draining = exchange("GET", "/_roots/health");
        server.handleTransport(draining);
        assertEquals(503, draining.status);
        assertTrue(draining.body().contains("DRAINING"));

        var rejected = exchange("GET", "/");
        server.handleTransport(rejected);
        assertEquals(503, rejected.status);
        assertTrue(rejected.body().contains("draining"));

        server.closeGracefully(Duration.ZERO);
        server.close();
        assertFalse(server.runtimeSnapshot().running());
        assertThrows(IllegalStateException.class, server::startRuntime);
    }

    @Test
    void closeBeforeStartIsIdempotentAndTerminal() {
        var server = new RootsServer(RootsConfig.forApplication(Application.class).build());
        server.close();
        server.close();
        assertFalse(server.runtimeSnapshot().running());
        assertThrows(IllegalStateException.class, server::startRuntime);
    }

    @Test
    void tracesAndObservesTransportResponsesWhileIsolatingObserverFailures() {
        var observations = new ArrayList<RequestObservation>();
        var server = new RootsServer(RootsConfig.forApplication(Application.class)
                .observeRequests(ignored -> {
                    throw new IllegalStateException("expected observer failure");
                })
                .observeRequests(observations::add)
                .build());
        var incoming = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        var exchange = exchange("GET", "/_roots/health", Map.of("TraceParent", List.of(incoming)));

        server.handleTransport(exchange);

        assertEquals(503, exchange.status);
        assertEquals(1, observations.size());
        var observation = observations.getFirst();
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", observation.traceContext().traceId());
        assertEquals("00f067aa0ba902b7", observation.traceContext().parentSpanId().orElseThrow());
        assertEquals(503, observation.status());
        assertEquals(exchange.responseHeaders.get("traceparent").getFirst(),
                observation.traceContext().traceparent());
        assertEquals(exchange.response.size(), observation.responseBytes());
        server.close();
    }

    @Test
    void observesConcurrentClosesExactlyOnceAndNormalizesBrokenTransportMetadata() throws Exception {
        var count = new AtomicInteger();
        var observations = new ArrayList<RequestObservation>();
        var exchange = new ObservedTransportExchange(
                new MemoryExchange(null, URI.create("relative"), Map.of()),
                List.of(observation -> {
                    count.incrementAndGet();
                    observations.add(observation);
                })
        );
        exchange.logicalPath("bad\npath");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var closes = new ArrayList<java.util.concurrent.Future<?>>();
            for (var index = 0; index < 100; index++) {
                closes.add(executor.submit(exchange::close));
            }
            for (var close : closes) {
                close.get();
            }
        }

        assertEquals(1, count.get());
        assertEquals("UNKNOWN", observations.getFirst().method());
        assertEquals("/", observations.getFirst().path());
        assertEquals("/", observations.getFirst().transportPath());
        assertEquals(0, observations.getFirst().status());
    }

    @Test
    void closesAndObservesAnExchangeWhenUnexpectedTransportMetadataEscapesDispatch() {
        var observations = new ArrayList<RequestObservation>();
        var server = new RootsServer(RootsConfig.forApplication(Application.class)
                .observeRequests(observations::add)
                .build());
        server.startRuntime();
        var broken = new MemoryExchange(null, URI.create("/"), Map.of());

        assertThrows(RuntimeException.class, () -> server.handleTransport(broken));

        assertTrue(broken.closed);
        assertEquals(1, observations.size());
        assertEquals("UNKNOWN", observations.getFirst().method());
        assertEquals(0, observations.getFirst().status());
        server.close();
    }

    @Test
    void rejectsUnsafeTransportMountsBeforeAllocatingASessionOrWritingACookie() {
        var server = new RootsServer(RootsConfig.forApplication(Application.class).build());
        server.startRuntime();
        var exchange = new MemoryExchange("GET", URI.create("/"), Map.of(), "/bad; Path=/");

        assertThrows(IllegalArgumentException.class, () -> server.handleTransport(exchange));

        assertTrue(exchange.closed);
        assertEquals(0, server.runtimeSnapshot().sessions());
        assertTrue(exchange.responseHeaders.getOrDefault("Set-Cookie", List.of()).isEmpty());
        server.close();
    }

    @Test
    void rebasesRootRelativeRedirectLocationsForMountedTransports() {
        var delegate = new MemoryExchange("GET", URI.create("/"), Map.of(), "/company/roots");
        var exchange = new ObservedTransportExchange(delegate, List.of());

        exchange.responseHeader("Location", List.of("/login", "https://identity.example/login", "//cdn.example/x"));

        assertEquals(
                List.of("/company/roots/login", "https://identity.example/login", "//cdn.example/x"),
                delegate.responseHeaders.get("Location")
        );
        exchange.close();
    }

    @Test
    void resolvesTrustedClientsAndRateLimitsBeforeSessionAllocation() {
        var server = new RootsServer(RootsConfig.forApplication(Application.class)
                .proxyPolicy(ProxyPolicy.trusted("127.0.0.0/8"))
                .rateLimiter(RateLimiter.fixedWindow(1, Duration.ofMinutes(1), 10))
                .instanceFactory(type -> type.equals(com.chaplin.roots.testapp.api.items.$itemId.Route.class)
                        ? new com.chaplin.roots.testapp.api.items.$itemId.Route("transport")
                        : InstanceFactory.reflection().create(type))
                .build());
        server.startRuntime();
        var headers = Map.of(
                "Forwarded", List.of("for=203.0.113.40;proto=https;host=app.example")
        );

        var first = exchange("GET", "/api/items/42", headers);
        server.handleTransport(first);
        assertEquals(200, first.status);
        assertEquals(List.of("1"), first.responseHeaders.get("RateLimit-Limit"));
        assertEquals(List.of("0"), first.responseHeaders.get("RateLimit-Remaining"));
        assertEquals(1, server.runtimeSnapshot().sessions());

        var rejected = exchange("GET", "/api/items/42", headers);
        server.handleTransport(rejected);
        assertEquals(429, rejected.status);
        assertEquals("60", rejected.responseHeaders.get("Retry-After").getFirst());
        assertTrue(rejected.responseHeaders.getOrDefault("Set-Cookie", List.of()).isEmpty());
        assertEquals(1, server.runtimeSnapshot().sessions());
        assertEquals(1, server.runtimeSnapshot().rejectedRequests());
        server.close();
    }

    @Test
    void ignoresSpoofedForwardingByDefaultAndBypassesLimitsForHealth() {
        var seen = new AtomicReference<ClientConnection>();
        var calls = new AtomicInteger();
        var limiter = (RateLimiter) request -> {
            calls.incrementAndGet();
            seen.set(request.connection());
            return RateLimitDecision.unlimited();
        };
        var server = new RootsServer(RootsConfig.forApplication(Application.class)
                .rateLimiter(limiter)
                .build());
        server.startRuntime();

        var health = exchange("GET", "/_roots/health", Map.of(
                "X-Forwarded-For", List.of("203.0.113.99")
        ));
        server.handleTransport(health);
        assertEquals(0, calls.get());

        var request = exchange("GET", "/public/app.css", Map.of(
                "X-Forwarded-For", List.of("203.0.113.99")
        ));
        server.handleTransport(request);
        assertEquals(1, calls.get());
        assertEquals("127.0.0.1", seen.get().clientAddressText());
        assertFalse(seen.get().forwarded());
        server.close();
    }

    @Test
    void failsClosedWhenTheConfiguredLimiterFails() {
        var server = new RootsServer(RootsConfig.forApplication(Application.class)
                .rateLimiter(ignored -> null)
                .build());
        server.startRuntime();

        var exchange = exchange("GET", "/");
        server.handleTransport(exchange);

        assertEquals(503, exchange.status);
        assertEquals(0, server.runtimeSnapshot().sessions());
        assertEquals(1, server.runtimeSnapshot().rejectedRequests());
        server.close();
    }

    @Test
    void closesWithoutAllocatingStateWhenAProxyPolicyIsBroken() {
        var server = new RootsServer(RootsConfig.forApplication(Application.class)
                .proxyPolicy((direct, headers) -> null)
                .build());
        server.startRuntime();
        var exchange = exchange("GET", "/");

        assertThrows(NullPointerException.class, () -> server.handleTransport(exchange));

        assertTrue(exchange.closed);
        assertEquals(0, server.runtimeSnapshot().sessions());
        server.close();
    }

    private static MemoryExchange exchange(String method, String path) {
        return exchange(method, path, Map.of());
    }

    private static MemoryExchange exchange(String method, String path, Map<String, List<String>> headers) {
        return new MemoryExchange(method, URI.create(path), headers);
    }

    private static final class MemoryExchange implements TransportExchange {
        private final String method;
        private final URI uri;
        private final Map<String, List<String>> requestHeaders;
        private final String mountPath;
        private final Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        private final ByteArrayOutputStream response = new ByteArrayOutputStream();
        private int status;
        private long length;
        private boolean closed;

        private MemoryExchange(String method, URI uri, Map<String, List<String>> requestHeaders) {
            this(method, uri, requestHeaders, "");
        }

        private MemoryExchange(
                String method,
                URI uri,
                Map<String, List<String>> requestHeaders,
                String mountPath
        ) {
            this.method = method;
            this.uri = uri;
            this.requestHeaders = Map.copyOf(requestHeaders);
            this.mountPath = mountPath;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public URI uri() {
            return uri;
        }

        @Override
        public Map<String, List<String>> requestHeaders() {
            return requestHeaders;
        }

        @Override
        public String mountPath() {
            return mountPath;
        }

        @Override
        public ClientConnection connection() {
            return ClientConnection.direct(InetAddress.ofLiteral("127.0.0.1"), "http", "localhost:8080");
        }

        @Override
        public String requestHeader(String name) {
            return requestHeaders.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public InputStream requestBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void responseHeader(String name, List<String> values) {
            responseHeaders.put(name, List.copyOf(values));
        }

        @Override
        public void sendResponseHeaders(int status, long length) {
            this.status = status;
            this.length = length;
        }

        @Override
        public OutputStream responseBody() {
            return response;
        }

        @Override
        public void close() {
            closed = true;
        }

        private String body() {
            assertTrue(closed);
            return response.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
