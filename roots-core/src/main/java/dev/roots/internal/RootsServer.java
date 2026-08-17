package dev.roots.internal;

import com.sun.net.httpserver.HttpServer;
import dev.roots.Request;
import dev.roots.BrowserEvent;
import dev.roots.LiveViewOwnership;
import dev.roots.LiveViewOwnershipException;
import dev.roots.RateLimitDecision;
import dev.roots.RateLimitRequest;
import dev.roots.Response;
import dev.roots.ResponseCookie;
import dev.roots.RootsConfig;
import dev.roots.RuntimeSnapshot;
import dev.roots.Session;
import dev.roots.SessionRepositoryException;
import dev.roots.ValidationException;
import dev.roots.UploadedFile;
import dev.roots.WebFont;
import dev.roots.html.HtmlRenderer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Internal embedded HTTP server exposed only through {@code RunningApplication}. */
public final class RootsServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RootsServer.class.getName());

    private final RootsConfig config;
    private final ConventionRouter router;
    private final PrerenderStore prerenders;
    private final SessionStore sessions;
    private final LiveViewOwnership ownership;
    private final Map<String, LiveView> views = new ConcurrentHashMap<>();
    private final Map<String, CachedAsset> assetCache = new ConcurrentHashMap<>();
    private final Map<String, CachedAsset> fontStylesheetCache = new LinkedHashMap<>(16, 0.75f, true);
    private final java.util.Set<TransportExchange> activeExchanges = ConcurrentHashMap.newKeySet();
    private final Semaphore requestCapacity;
    private final Semaphore viewCapacity;
    private final CachedAsset clientAsset;
    private final CachedAsset clientStyle;
    private final CachedAsset developmentStyle;
    private final ImageOptimizer imageOptimizer;
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong rejectedRequestCount = new AtomicLong();
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final AtomicInteger peakActiveRequests = new AtomicInteger();
    private final AtomicBoolean acceptingRequests = new AtomicBoolean();
    private final AtomicBoolean runtimeStarted = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object requestCompletion = new Object();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("roots-maintenance").factory()
    );
    private final long maintenanceIntervalMillis;
    private final Duration streamHeartbeat;
    private volatile HttpServer server;

    /** Creates and discovers an embedded server without starting its listener.
     * @param config application configuration */
    public RootsServer(RootsConfig config) {
        this.config = config;
        router = ConventionRouter.discover(config);
        prerenders = PrerenderStore.create(config, router);
        sessions = new SessionStore(
                config.sessionRepository(),
                config.sessionTimeout(),
                config.secureCookies(),
                config.maxSessions()
        );
        ownership = config.liveViewOwnership();
        requestCapacity = new Semaphore(config.maxConcurrentRequests(), true);
        viewCapacity = new Semaphore(config.maxLiveViews(), true);
        clientAsset = cachedAsset(
                "text/javascript; charset=utf-8",
                config.development() ? "no-store" : "public, max-age=3600",
                ClientRuntime.SOURCE.getBytes(StandardCharsets.UTF_8)
        );
        clientStyle = cachedAsset(
                "text/css; charset=utf-8",
                config.development() ? "no-store" : "public, max-age=3600",
                ClientRuntime.CSS.getBytes(StandardCharsets.UTF_8)
        );
        developmentStyle = cachedAsset(
                "text/css; charset=utf-8",
                "no-store",
                ClientRuntime.DEVELOPMENT_CSS.getBytes(StandardCharsets.UTF_8)
        );
        imageOptimizer = new ImageOptimizer(config.applicationClass().getClassLoader(), config.development());
        var shortestTimeout = Math.min(config.viewTimeout().toMillis(), config.sessionTimeout().toMillis());
        maintenanceIntervalMillis = Math.max(10, Math.min(60_000, shortestTimeout / 2));
        streamHeartbeat = Duration.ofMillis(Math.max(1, Math.min(20_000, config.viewTimeout().toMillis() / 2)));
    }

    /** Starts the HTTP listener and maintenance scheduler. */
    public void start() {
        if (server != null) {
            throw new IllegalStateException("Roots server already started");
        }
        try {
            server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> handleTransport(new JdkTransportExchange(exchange)));
            startRuntime();
            server.start();
        } catch (IOException exception) {
            acceptingRequests.set(false);
            throw new IllegalStateException("Could not start Roots on " + config.host() + ":" + config.port(), exception);
        }
    }

    /** Starts the listener-independent runtime for a separately packaged transport adapter. */
    public void startRuntime() {
        if (closed.get()) {
            throw new IllegalStateException("Roots runtime is closed");
        }
        if (!runtimeStarted.compareAndSet(false, true)) {
            throw new IllegalStateException("Roots runtime already started");
        }
        acceptingRequests.set(true);
        maintenance.scheduleWithFixedDelay(
                this::maintenance,
                maintenanceIntervalMillis,
                maintenanceIntervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    /** Handles one adapter-provided HTTP exchange.
     * @param exchange transport exchange */
    public void handleTransport(TransportExchange exchange) {
        Objects.requireNonNull(exchange, "exchange");
        try {
            exchange = new ObservedTransportExchange(exchange, config.requestObservers(), config.proxyPolicy());
            if (!runtimeStarted.get()) {
                send(exchange, unavailable("Roots runtime is not running"), false);
                return;
            }
            handle(exchange);
        } catch (RuntimeException | Error failure) {
            exchange.close();
            throw failure;
        }
    }

    /** Returns the bound application address.
     * @return listening URI */
    public URI uri() {
        if (server == null) {
            throw new IllegalStateException("Roots server has not started");
        }
        var visibleHost = config.host().equals("0.0.0.0") ? "localhost" : config.host();
        return URI.create("http://" + visibleHost + ":" + server.getAddress().getPort());
    }

    /** Returns discovered route descriptions.
     * @return routes */
    public List<String> routes() {
        return router.routes();
    }

    /** Captures current operational counters.
     * @return runtime snapshot */
    public RuntimeSnapshot runtimeSnapshot() {
        return new RuntimeSnapshot(
                runtimeStarted.get(),
                requestCount.get(),
                views.size(),
                sessions.size(),
                acceptingRequests.get(),
                rejectedRequestCount.get(),
                activeRequests.get(),
                peakActiveRequests.get(),
                config.maxLiveViews(),
                config.maxSessions(),
                config.maxConcurrentRequests()
        );
    }

    /** Returns the application cache.
     * @return cache */
    public dev.roots.RootsCache cache() {
        return config.cache();
    }

    /** Returns the live-view affinity identifier for this runtime.
     * @return node identifier */
    public String nodeId() {
        return ownership.localNodeId();
    }

    /** Marks readiness down and rejects new application work. */
    public void beginDrain() {
        acceptingRequests.set(false);
    }

    /** Drains and stops the embedded server.
     * @param timeout maximum graceful wait */
    public void closeGracefully(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Shutdown timeout must not be negative");
        }
        beginDrain();
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        final HttpServer stopping;
        synchronized (this) {
            stopping = server;
            server = null;
        }
        runtimeStarted.set(false);
        var deadline = deadline(timeout);
        var cleaners = new ArrayList<Thread>();
        views.forEach((id, view) -> detachView(id, view, cleaners));
        awaitRequests(deadline);
        if (stopping != null) {
            stopping.stop(0);
        }
        activeExchanges.forEach(TransportExchange::close);
        maintenance.shutdownNow();
        executor.shutdownNow();
        views.forEach((id, view) -> detachView(id, view, cleaners));
        awaitCleaners(cleaners, deadline);
        prerenders.close();
        sessions.clear();
        ownership.close();
    }

    @Override
    public void close() {
        closeGracefully(Duration.ZERO);
    }

    private void handle(TransportExchange exchange) {
        if (!requestCapacity.tryAcquire()) {
            rejectedRequestCount.incrementAndGet();
            requestCount.incrementAndGet();
            send(exchange, unavailable("Request capacity is exhausted"), false);
            return;
        }
        var active = activeRequests.incrementAndGet();
        activeExchanges.add(exchange);
        peakActiveRequests.accumulateAndGet(active, Math::max);
        try {
            handleExchange(exchange);
        } finally {
            activeExchanges.remove(exchange);
            activeRequests.decrementAndGet();
            requestCapacity.release();
            synchronized (requestCompletion) {
                requestCompletion.notifyAll();
            }
            if ((requestCount.incrementAndGet() & 127) == 0) {
                cleanup();
            }
        }
    }

    private void handleExchange(TransportExchange exchange) {
        var rawPath = exchange.uri().getRawPath();
        if (rawPath.equals("/_roots/health")) {
            send(exchange, health(exchange), exchange.method().equals("HEAD"));
            return;
        }
        if (!acceptingRequests.get()) {
            rejectedRequestCount.incrementAndGet();
            send(exchange, unavailable("Roots is draining"), false);
            return;
        }
        var rateLimitRequest = new RateLimitRequest(exchange.method(), rawPath, exchange.connection());
        final RateLimitDecision rateLimit;
        try {
            rateLimit = Objects.requireNonNull(
                    config.rateLimiter().acquire(rateLimitRequest),
                    "Rate limiter decision"
            );
        } catch (RuntimeException exception) {
            rejectedRequestCount.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING, "Rate limiter unavailable", exception);
            send(exchange, unavailable("Rate limiter is unavailable"), false);
            return;
        }
        rateLimitHeaders(exchange, rateLimit);
        if (!rateLimit.allowed()) {
            rejectedRequestCount.incrementAndGet();
            send(exchange, Response.text(429, "Too many requests")
                    .withHeader("Cache-Control", "no-store")
                    .withHeader("Retry-After", Long.toString(resetSeconds(rateLimit))),
                    exchange.method().equals("HEAD"));
            return;
        }
        if (config.development() && rawPath.equals("/_roots/development")) {
            developmentStream(exchange);
            return;
        }
        try {
            var publicResponse = sessionFreeResponse(exchange, rawPath);
            if (publicResponse != null) {
                send(exchange, publicResponse, exchange.method().equals("HEAD"));
                return;
            }
        } catch (IOException exception) {
            send(exchange, errorResponse(exception, acceptsJson(exchange)), false);
            return;
        }
        var invalidProtocolMethod = invalidProtocolMethod(exchange.method(), rawPath);
        if (invalidProtocolMethod != null) {
            send(exchange, invalidProtocolMethod, false);
            return;
        }
        final SessionStore.ResolvedSession resolvedSession;
        try {
            if (isProtocolPath(rawPath)) {
                var existing = sessions.resolveExisting(exchange);
                if (existing.isEmpty()) {
                    send(exchange, missingProtocolSession(rawPath), false);
                    return;
                }
                resolvedSession = existing.orElseThrow();
            } else {
                resolvedSession = sessions.resolve(exchange);
            }
        } catch (SessionStore.CapacityExceededException exception) {
            rejectedRequestCount.incrementAndGet();
            send(exchange, unavailable("Session capacity is exhausted"), false);
            return;
        } catch (SessionRepositoryException exception) {
            rejectedRequestCount.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING, "Session repository unavailable", exception);
            send(exchange, unavailable("Session repository is unavailable"), false);
            return;
        }
        if (exchange.uri().getRawPath().equals("/_roots/stream")) {
            stream(exchange, resolvedSession.session());
            return;
        }
        if (config.development() && exchange.uri().getRawPath().equals("/_roots/inspect")) {
            inspect(exchange, resolvedSession.session());
            return;
        }
        Response response;
        Request request = null;
        try {
            request = HttpSupport.request(
                    exchange,
                    Map.of(),
                    resolvedSession.session(),
                    config.maxRequestBytes(),
                    config.cache(),
                    config.requestBodyPolicy()
            );
            request = authenticate(request);
            request = logicalRequest(request);
            observeLogicalPath(exchange, request.path());
            var affinityResponse = affinityResponse(request);
            response = affinityResponse == null ? middleware(exchange, request, 0) : affinityResponse;
        } catch (HttpSupport.RequestTooLargeException exception) {
            response = Response.text(413, "Request body is too large");
        } catch (HttpSupport.BadRequestException exception) {
            response = Response.text(400, exception.getMessage());
        } catch (LiveViewOwnershipException exception) {
            rejectedRequestCount.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING, "Live-view ownership registry unavailable", exception);
            response = unavailable("Live-view ownership registry is unavailable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!acceptingRequests.get()) {
                exchange.close();
                return;
            }
            response = mappedErrorResponse(request, exception, acceptsJson(exchange));
        } catch (Throwable exception) {
            response = mappedErrorResponse(request, exception, acceptsJson(exchange));
        }

        if (resolvedSession.isNew()) {
            response = response.withHeader("Set-Cookie", resolvedSession.setCookieHeader());
        }
        closeRequest(request);
        send(exchange, response, exchange.method().equals("HEAD"));
    }

    private static void rateLimitHeaders(TransportExchange exchange, RateLimitDecision decision) {
        if (decision.limit() == 0) {
            return;
        }
        exchange.responseHeader("RateLimit-Limit", Long.toString(decision.limit()));
        exchange.responseHeader("RateLimit-Remaining", Long.toString(decision.remaining()));
        exchange.responseHeader("RateLimit-Reset", Long.toString(resetSeconds(decision)));
    }

    private static long resetSeconds(RateLimitDecision decision) {
        var duration = decision.resetAfter();
        var seconds = duration.getSeconds();
        return duration.getNano() == 0 ? seconds : seconds == Long.MAX_VALUE ? seconds : seconds + 1;
    }

    private Response middleware(TransportExchange exchange, Request request, int index) throws Exception {
        if (index >= config.middleware().size()) {
            return dispatch(exchange, request);
        }
        var current = config.middleware().get(index);
        var invoked = new boolean[1];
        var response = current.handle(request, () -> {
            if (invoked[0]) {
                throw new IllegalStateException("Middleware may invoke its chain only once");
            }
            invoked[0] = true;
            return middleware(exchange, request, index + 1);
        });
        if (response == null) {
            throw new IllegalStateException("Middleware returned null: " + current.getClass().getName());
        }
        return response;
    }

    private Request logicalRequest(Request request) {
        if (!request.method().equals("POST")
                || (!request.transportPath().equals("/_roots/action")
                && !request.transportPath().equals("/_roots/dispose"))) {
            return request;
        }
        var viewId = request.formValue("_view").orElse(null);
        var csrf = request.formValue("_csrf").orElse(null);
        if (viewId == null || csrf == null) {
            return request;
        }
        var view = views.get(viewId);
        if (view == null
                || !view.sessionId().equals(request.session().id())
                || !view.mountPath().equals(request.mountPath())
                || !MessageDigest.isEqual(
                        view.csrf().getBytes(StandardCharsets.UTF_8),
                        csrf.getBytes(StandardCharsets.UTF_8))) {
            return request;
        }
        return request.withLogicalRoute(view.path(), view.parameters(), view.query());
    }

    private Response affinityResponse(Request request) {
        if (!request.method().equals("POST")
                || (!request.transportPath().equals("/_roots/action")
                && !request.transportPath().equals("/_roots/dispose"))) {
            return null;
        }
        var viewId = request.formValue("_view").orElse(null);
        if (viewId == null
                || request.formValue("_csrf").isEmpty()
                || !Protocol.matches(request.formValue(Protocol.FORM_FIELD).orElse(null))
                || views.containsKey(viewId)) {
            return null;
        }
        var owner = ownership.find(viewId, Instant.now()).orElse(null);
        if (owner == null
                || !owner.sessionId().equals(request.session().id())
                || owner.nodeId().equals(ownership.localNodeId())) {
            return null;
        }
        return wrongNode(owner.nodeId());
    }

    private static void observeLogicalPath(TransportExchange exchange, String path) {
        if (exchange instanceof ObservedTransportExchange observed) {
            observed.logicalPath(path);
        }
    }

    private Response dispatch(TransportExchange exchange, Request request) throws Exception {
        var rawPath = exchange.uri().getRawPath();
        if (rawPath.equals("/_roots/client.js")) {
            return javascript();
        }
        if (rawPath.equals("/_roots/action")) {
            return action(request);
        }
        if (rawPath.equals("/_roots/dispose")) {
            return dispose(request);
        }
        var api = router.api(rawPath);
        if (api.isPresent()) {
            return api(request, api.orElseThrow());
        }
        if (exchange.method().equals("GET") || exchange.method().equals("HEAD")) {
            var page = router.page(rawPath);
            if (page.isPresent()) {
                return page(request, page.orElseThrow());
            }
            var asset = asset(rawPath);
            if (asset != null) {
                return asset.response();
            }
            var notFound = router.notFound();
            if (notFound.isPresent()) {
                return page(request, notFound.orElseThrow(), 404);
            }
            return notFound(rawPath);
        }
        return Response.methodNotAllowed(exchange.method())
                .withHeader("Allow", "GET, HEAD");
    }

    private Response page(Request request, ConventionRouter.PageMatch match) throws Exception {
        return page(request, match, 200);
    }

    private Response page(Request request, ConventionRouter.PageMatch match, int status) throws Exception {
        var routedRequest = request.withParameters(match.parameters());
        var rejection = authorize(routedRequest, match.route().authorizationPolicies());
        if (rejection != null) {
            return rejection;
        }
        if (request.method().equals("HEAD")) {
            var view = new LiveView(
                    match,
                    request.path(),
                    request.query(),
                    request.session(),
                    config.instanceFactory(),
                    config.authorizationPolicies().keySet(),
                    config.cache(),
                    request.identity(),
                    request.traceContext(),
                    request.mountPath(),
                    request.connection(),
                    request.cookies()
            );
            try {
                return document(status, view, view.snapshot())
                        .withHeader("Cache-Control", "no-store");
            } finally {
                view.close();
            }
        }
        if (!viewCapacity.tryAcquire()) {
            rejectedRequestCount.incrementAndGet();
            return unavailable("Live view capacity is exhausted");
        }
        LiveView view = null;
        var registered = false;
        var claimed = false;
        try {
            view = new LiveView(
                    match,
                    request.path(),
                    request.query(),
                    request.session(),
                    config.instanceFactory(),
                    config.authorizationPolicies().keySet(),
                    config.cache(),
                    request.identity(),
                    request.traceContext(),
                    request.mountPath(),
                    request.connection(),
                    request.cookies()
            );
            var response = document(status, view, view.snapshot())
                    .withHeader("Cache-Control", "no-store")
                    .withHeader("X-Roots-Node", ownership.localNodeId());
            var now = Instant.now();
            if (!ownership.claim(view.id(), view.sessionId(), now, now.plus(config.viewTimeout()))) {
                rejectedRequestCount.incrementAndGet();
                return unavailable("Live-view ownership conflict");
            }
            claimed = true;
            if (views.putIfAbsent(view.id(), view) != null) {
                throw new IllegalStateException("Generated a duplicate live view identifier");
            }
            registered = true;
            return response;
        } finally {
            if (!registered) {
                if (claimed && view != null) {
                    ownership.release(view.id(), view.sessionId());
                }
                viewCapacity.release();
                if (view != null) {
                    view.close();
                }
            }
        }
    }

    private Response action(Request request) throws Exception {
        if (!request.method().equals("POST")) {
            return Response.methodNotAllowed(request.method()).withHeader("Allow", "POST");
        }
        var values = request.form();
        final String viewId;
        final String csrf;
        final String actionName;
        final BrowserEvent browserEvent;
        try {
            viewId = first(values, "_view");
            csrf = first(values, "_csrf");
            requireProtocol(optionalProtocolValue(values, Protocol.FORM_FIELD).orElse(null));
            actionName = first(values, "_action");
            browserEvent = browserEvent(values, first(values, "_event"));
        } catch (ProtocolMismatchException exception) {
            return protocolMismatch();
        } catch (IllegalArgumentException exception) {
            return Response.json(400, "{\"error\":\"Malformed Roots action request\"}");
        }
        var view = views.get(viewId);
        if (view == null) {
            return missingView(viewId, request.session());
        }
        if (!view.sessionId().equals(request.session().id())
                || !view.mountPath().equals(request.mountPath())
                || !MessageDigest.isEqual(
                        view.csrf().getBytes(StandardCharsets.UTF_8),
                        csrf.getBytes(StandardCharsets.UTF_8))) {
            return Response.json(409, "{\"error\":\"This live view has expired\"}");
        }
        if (!renewOwnership(viewId)) {
            removeView(viewId, view);
            return missingView(viewId, request.session());
        }
        var publicValues = new LinkedHashMap<String, List<String>>();
        values.forEach((name, value) -> {
            if (!name.startsWith("_")) {
                publicValues.put(name, value);
            }
        });
        var publicFiles = new LinkedHashMap<String, List<UploadedFile>>();
        request.files().forEach((name, value) -> {
            if (!name.startsWith("_")) {
                publicFiles.put(name, value);
            }
        });
        try {
            var result = view.invoke(
                    actionName,
                    browserEvent,
                    Map.copyOf(publicValues),
                    Map.copyOf(publicFiles),
                    request.session(),
                    request.identity(),
                    request.traceContext(),
                    request.connection(),
                    request.cookies(),
                    policy -> authorizePolicy(request, policy)
            );
            if (result.redirect() != null) {
                return withResponseCookies(Response.json(200, "{\"protocol\":" + HttpSupport.jsonString(Protocol.VERSION)
                                + ",\"view\":" + HttpSupport.jsonString(view.id())
                                + ",\"redirect\":" + HttpSupport.jsonString(result.redirect())
                                + ",\"effects\":" + effectsJson(result.effects()) + "}")
                        .withHeader("Cache-Control", "no-store"), result.responseCookies());
            }
            return withResponseCookies(
                    Response.json(200, patchJson(view, result.snapshot(), result.effects()))
                            .withHeader("Cache-Control", "no-store"),
                    result.responseCookies()
            );
        } catch (LiveView.StaleViewException exception) {
            return Response.json(409, "{\"error\":\"This live view has expired\"}");
        } catch (LiveView.AuthorizationRejected exception) {
            return exception.response().withHeader("Cache-Control", "no-store");
        } catch (ValidationException exception) {
            return validationResponse(exception);
        } catch (IllegalArgumentException exception) {
            return Response.json(422, "{\"error\":" + HttpSupport.jsonString(exception.getMessage()) + "}");
        } finally {
            if (view.closed()) {
                removeView(viewId, view);
            }
        }
    }

    private Response dispose(Request request) {
        if (!request.method().equals("POST")) {
            return Response.methodNotAllowed(request.method()).withHeader("Allow", "POST");
        }
        var viewId = request.formValue("_view").orElse(null);
        var csrf = request.formValue("_csrf").orElse(null);
        var protocol = request.formValue(Protocol.FORM_FIELD).orElse(null);
        if (!Protocol.matches(protocol)) {
            return protocolMismatch();
        }
        if (viewId != null && csrf != null) {
            var view = views.get(viewId);
            if (view == null) {
                return missingView(viewId, request.session());
            }
            if (view.sessionId().equals(request.session().id())
                    && view.mountPath().equals(request.mountPath())
                    && MessageDigest.isEqual(
                            view.csrf().getBytes(StandardCharsets.UTF_8),
                            csrf.getBytes(StandardCharsets.UTF_8))
                    ) {
                removeView(viewId, view);
            }
        }
        return Response.noContent().withHeader("Cache-Control", "no-store");
    }

    private Response api(Request request, ConventionRouter.ApiMatch match) throws Exception {
        var routedRequest = request.withParameters(match.parameters());
        var rejection = authorize(routedRequest, match.route().authorizationPolicies());
        if (rejection != null) {
            return rejection;
        }
        var route = config.instanceFactory().instantiate(match.route().type());
        final Response response;
        try {
            response = route.handle(routedRequest).withHeader("Cache-Control", "no-store");
        } catch (Exception | Error failure) {
            try {
                config.instanceFactory().destroy(route);
            } catch (Exception destroyFailure) {
                failure.addSuppressed(destroyFailure);
            }
            throw failure;
        }
        config.instanceFactory().destroy(route);
        return response;
    }

    private Response javascript() {
        return clientAsset.response();
    }

    private void stream(TransportExchange exchange, Session session) {
        LiveView attachedView = null;
        LiveView.StreamAttachment streamAttachment = null;
        Request streamRequest = null;
        try {
            exchange.responseHeader(Protocol.RESPONSE_HEADER, Protocol.VERSION);
            exchange.responseHeader("X-Roots-Node", ownership.localNodeId());
            if (!exchange.method().equals("GET")) {
                HttpSupport.send(exchange, Response.methodNotAllowed(exchange.method()).withHeader("Allow", "GET"), false);
                return;
            }
            var query = HttpSupport.parameters(exchange.uri().getRawQuery());
            var protocol = optionalProtocolValue(query, Protocol.QUERY_PARAMETER).orElse(null);
            if (!Protocol.matches(protocol)) {
                protocolReload(exchange);
                return;
            }
            var viewId = first(query, "view");
            var csrf = first(query, "csrf");
            var view = views.get(viewId);
            if (view == null) {
                HttpSupport.send(exchange, missingView(viewId, session), false);
                return;
            }
            if (!view.sessionId().equals(session.id())
                    || !view.mountPath().equals(exchange.mountPath())
                    || !MessageDigest.isEqual(view.csrf().getBytes(StandardCharsets.UTF_8), csrf.getBytes(StandardCharsets.UTF_8))) {
                HttpSupport.send(exchange, Response.text(404, "Live view not found"), false);
                return;
            }
            if (!renewOwnership(viewId)) {
                removeView(viewId, view);
                HttpSupport.send(exchange, missingView(viewId, session), false);
                return;
            }
            streamRequest = authenticate(HttpSupport.request(
                    exchange,
                    view.parameters(),
                    session,
                    config.maxRequestBytes(),
                    config.cache(),
                    config.requestBodyPolicy()
            ))
                    .withLogicalRoute(view.path(), view.parameters(), view.query());
            observeLogicalPath(exchange, streamRequest.path());
            if (!view.identity().equals(streamRequest.identity())) {
                removeView(viewId, view);
                HttpSupport.send(exchange, Response.text(409, "This live view has expired")
                        .withHeader("Cache-Control", "no-store"), false);
                return;
            }
            var rejection = authorize(streamRequest, view.authorizationPolicies());
            if (rejection != null) {
                HttpSupport.send(exchange, rejection, false);
                return;
            }
            streamAttachment = view.attachStream();
            attachedView = view;
            exchange.responseHeader("Content-Type", "text/event-stream; charset=utf-8");
            exchange.responseHeader("Cache-Control", "no-store");
            exchange.responseHeader("Connection", "keep-alive");
            exchange.responseHeader("X-Accel-Buffering", "no");
            exchange.responseHeader(Protocol.RESPONSE_HEADER, Protocol.VERSION);
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.responseBody()) {
                var initial = ": connected\n\nevent: patch\ndata: "
                        + patchJson(view, view.reconnectSnapshot()) + "\n\n";
                output.write(initial.getBytes(StandardCharsets.UTF_8));
                output.flush();
                while (views.get(viewId) == view && view.streamAttached(streamAttachment)) {
                    var patch = view.nextPatch(streamHeartbeat);
                    if (!renewOwnership(viewId)) {
                        removeView(viewId, view);
                        break;
                    }
                    if (patch != null && patch.closed()) {
                        break;
                    }
                    if (authorize(streamRequest, view.authorizationPolicies()) != null) {
                        break;
                    }
                    var message = patch == null
                            ? ": heartbeat\n\n"
                            : "event: patch\ndata: " + patchJson(view, patch.snapshot()) + "\n\n";
                    output.write(message.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            }
        } catch (IOException exception) {
            // Browser navigation and tab closure normally end an SSE connection this way.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (HttpSupport.BadRequestException exception) {
            try {
                HttpSupport.send(exchange, Response.text(400, exception.getMessage()), false);
            } catch (IOException ignored) {
                exchange.close();
            }
        } catch (LiveViewOwnershipException exception) {
            LOG.log(System.Logger.Level.WARNING, "Live-view ownership registry unavailable", exception);
            try {
                HttpSupport.send(exchange, unavailable("Live-view ownership registry is unavailable"), false);
            } catch (IOException ignored) {
                exchange.close();
            }
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "Live update stream failed", exception);
            exchange.close();
        } catch (Exception exception) {
            LOG.log(System.Logger.Level.WARNING, "Live update stream authorization failed", exception);
            exchange.close();
        } finally {
            if (attachedView != null && streamAttachment != null) {
                attachedView.detachStream(streamAttachment);
            }
            closeRequest(streamRequest);
        }
    }

    private void inspect(TransportExchange exchange, Session session) {
        Request inspectRequest = null;
        try {
            var query = HttpSupport.parameters(exchange.uri().getRawQuery());
            requireProtocol(optionalProtocolValue(query, Protocol.QUERY_PARAMETER).orElse(null));
            var viewId = first(query, "view");
            var csrf = first(query, "csrf");
            var view = views.get(viewId);
            if (view == null) {
                send(exchange, missingView(viewId, session), false);
                return;
            }
            if (!view.sessionId().equals(session.id())
                    || !view.mountPath().equals(exchange.mountPath())
                    || !MessageDigest.isEqual(
                    view.csrf().getBytes(StandardCharsets.UTF_8),
                    csrf.getBytes(StandardCharsets.UTF_8))) {
                send(exchange, Response.text(404, "Live view not found").withHeader("Cache-Control", "no-store"), false);
                return;
            }
            if (!renewOwnership(viewId)) {
                removeView(viewId, view);
                send(exchange, missingView(viewId, session), false);
                return;
            }
            inspectRequest = authenticate(HttpSupport.request(
                    exchange,
                    view.parameters(),
                    session,
                    config.maxRequestBytes(),
                    config.cache(),
                    config.requestBodyPolicy()
            )).withLogicalRoute(view.path(), view.parameters(), view.query());
            observeLogicalPath(exchange, inspectRequest.path());
            if (!view.identity().equals(inspectRequest.identity())) {
                removeView(viewId, view);
                send(exchange, Response.text(409, "This live view has expired")
                        .withHeader("Cache-Control", "no-store"), false);
                return;
            }
            var rejection = authorize(inspectRequest, view.authorizationPolicies());
            if (rejection != null) {
                send(exchange, rejection, false);
                return;
            }
            send(exchange, Response.json(200, inspectionJson(view.inspect()))
                    .withHeader("Cache-Control", "no-store"), false);
        } catch (ProtocolMismatchException exception) {
            send(exchange, protocolMismatch(), false);
        } catch (LiveViewOwnershipException exception) {
            LOG.log(System.Logger.Level.WARNING, "Live-view ownership registry unavailable", exception);
            send(exchange, unavailable("Live-view ownership registry is unavailable"), false);
        } catch (HttpSupport.BadRequestException | IllegalArgumentException exception) {
            send(exchange, Response.text(400, exception.getMessage()).withHeader("Cache-Control", "no-store"), false);
        } catch (Exception exception) {
            send(exchange, mappedErrorResponse(null, exception, true), false);
        } finally {
            closeRequest(inspectRequest);
        }
    }

    private static void closeRequest(Request request) {
        if (request == null) {
            return;
        }
        try {
            request.close();
        } catch (RuntimeException cleanupFailure) {
            LOG.log(System.Logger.Level.WARNING, "Could not release request content", cleanupFailure);
        }
    }

    private static String inspectionJson(LiveView.Inspection inspection) {
        var json = new StringBuilder("{")
                .append("\"protocol\":").append(HttpSupport.jsonString(Protocol.VERSION))
                .append(",\"path\":").append(HttpSupport.jsonString(inspection.path()))
                .append(",\"revision\":").append(inspection.revision())
                .append(",\"page\":").append(HttpSupport.jsonString(inspection.pageType()))
                .append(",\"layouts\":").append(stringsJson(inspection.layoutTypes()))
                .append(",\"components\":[");
        for (var index = 0; index < inspection.components().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            var component = inspection.components().get(index);
            json.append("{\"name\":").append(HttpSupport.jsonString(component.name()))
                    .append(",\"type\":").append(HttpSupport.jsonString(component.type()))
                    .append(",\"identity\":").append(HttpSupport.jsonString(component.identity()))
                    .append(",\"occurrences\":").append(component.occurrences()).append('}');
        }
        json.append("],\"actions\":[");
        var index = 0;
        for (var entry : inspection.actions().entrySet()) {
            if (index++ > 0) {
                json.append(',');
            }
            var action = entry.getValue();
            json.append("{\"wire\":").append(HttpSupport.jsonString(entry.getKey()))
                    .append(",\"target\":").append(HttpSupport.jsonString(action.targetType()))
                    .append(",\"method\":").append(HttpSupport.jsonString(action.method()))
                    .append(",\"policies\":").append(stringsJson(action.authorizationPolicies()))
                    .append('}');
        }
        return json.append("]}").toString();
    }

    private static String stringsJson(List<String> values) {
        var json = new StringBuilder("[");
        for (var index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(HttpSupport.jsonString(values.get(index)));
        }
        return json.append(']').toString();
    }

    private void developmentStream(TransportExchange exchange) {
        try {
            if (!exchange.method().equals("GET")) {
                HttpSupport.send(exchange, Response.methodNotAllowed(exchange.method())
                        .withHeader("Allow", "GET")
                        .withHeader("Cache-Control", "no-store"), false);
                return;
            }
            var query = HttpSupport.parameters(exchange.uri().getRawQuery());
            var sinceValues = query.get("since");
            var sinceValue = sinceValues == null || sinceValues.size() != 1 ? null : sinceValues.getFirst();
            final long since;
            try {
                if (sinceValue == null) {
                    throw new NumberFormatException("missing");
                }
                since = Long.parseLong(sinceValue);
                if (since < 0) {
                    throw new NumberFormatException("negative");
                }
            } catch (NumberFormatException exception) {
                HttpSupport.send(exchange, Response.text(400, "Development event version must be a non-negative integer")
                        .withHeader("Cache-Control", "no-store"), false);
                return;
            }
            var applicationName = config.applicationClass().getName();
            if (since > DevelopmentEvents.current(applicationName).version()) {
                HttpSupport.send(exchange, Response.text(400, "Development event version is ahead of the server")
                        .withHeader("Cache-Control", "no-store"), false);
                return;
            }
            exchange.responseHeader("Content-Type", "text/event-stream; charset=utf-8");
            exchange.responseHeader("Cache-Control", "no-store");
            exchange.responseHeader("Connection", "keep-alive");
            exchange.responseHeader("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.responseBody()) {
                output.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                var observed = since;
                var current = DevelopmentEvents.current(applicationName);
                if (current.kind() == DevelopmentEvents.Kind.FAILURE && current.version() == observed) {
                    output.write(developmentMessage(current).getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                while (acceptingRequests.get()) {
                    var event = DevelopmentEvents.awaitAfter(applicationName, observed, Duration.ofSeconds(10));
                    if (event.isEmpty()) {
                        output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        continue;
                    }
                    observed = event.orElseThrow().version();
                    output.write(developmentMessage(event.orElseThrow()).getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    if (event.orElseThrow().kind() == DevelopmentEvents.Kind.RELOAD) {
                        break;
                    }
                }
            }
        } catch (IOException exception) {
            // Reload, navigation, and tab closure normally end the development stream this way.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (HttpSupport.BadRequestException exception) {
            try {
                HttpSupport.send(exchange, Response.text(400, exception.getMessage()), false);
            } catch (IOException ignored) {
                exchange.close();
            }
        }
    }

    private static String developmentMessage(DevelopmentEvents.Event event) {
        var eventName = event.kind() == DevelopmentEvents.Kind.RELOAD ? "reload" : "compile-error";
        return "id: " + event.version() + "\n"
                + "event: " + eventName + "\n"
                + "data: {\"version\":" + event.version()
                + ",\"message\":" + HttpSupport.jsonString(event.message()) + "}\n\n";
    }

    private CachedAsset asset(String path) throws IOException {
        if (!validAssetPath(path)) {
            return null;
        }
        if (!config.development()) {
            var cached = assetCache.get(path);
            if (cached != null) {
                return cached;
            }
        }
        var resourceName = "public" + (path.equals("/") ? "/index.html" : path);
        try (var input = config.applicationClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                return null;
            }
            var contentType = switch (extension(path)) {
                case "html", "htm" -> "text/html; charset=utf-8";
                case "css" -> "text/css; charset=utf-8";
                case "js" -> "text/javascript; charset=utf-8";
                case "json" -> "application/json; charset=utf-8";
                case "txt" -> "text/plain; charset=utf-8";
                case "csv" -> "text/csv; charset=utf-8";
                case "xml" -> "application/xml; charset=utf-8";
                case "webmanifest" -> "application/manifest+json; charset=utf-8";
                case "svg" -> "image/svg+xml";
                case "png" -> "image/png";
                case "jpg", "jpeg" -> "image/jpeg";
                case "webp" -> "image/webp";
                case "avif" -> "image/avif";
                case "gif" -> "image/gif";
                case "ico" -> "image/x-icon";
                case "woff" -> "font/woff";
                case "woff2" -> "font/woff2";
                case "ttf" -> "font/ttf";
                case "otf" -> "font/otf";
                case "pdf" -> "application/pdf";
                default -> "application/octet-stream";
            };
            var loaded = cachedAsset(
                    contentType,
                    config.development() ? "no-store" : "public, max-age=3600",
                    input.readAllBytes()
            );
            if (config.development()) {
                return loaded;
            }
            var previous = assetCache.putIfAbsent(path, loaded);
            return previous == null ? loaded : previous;
        }
    }

    private Response document(int status, LiveView view, LiveView.Snapshot snapshot) {
        return Response.html(status, DocumentRenderer.live(
                config,
                view.id(),
                view.csrf(),
                snapshot.revision(),
                snapshot.html(),
                snapshot.metadata(),
                view.mountPath()
        ));
    }

    private String patchJson(LiveView view, LiveView.Snapshot snapshot) {
        return patchJson(view, snapshot, List.of());
    }

    private String patchJson(
            LiveView view,
            LiveView.Snapshot snapshot,
            List<dev.roots.ClientEffect> effects
    ) {
        var metadata = snapshot.metadata();
        return "{"
                + "\"protocol\":" + HttpSupport.jsonString(Protocol.VERSION) + ","
                + "\"view\":" + HttpSupport.jsonString(view.id()) + ","
                + "\"html\":" + HttpSupport.jsonString(snapshot.patchHtml()) + ","
                + "\"scope\":" + HttpSupport.jsonString(snapshot.patchScope()) + ","
                + "\"title\":" + HttpSupport.jsonString(metadata.document().title()) + ","
                + "\"description\":" + HttpSupport.jsonString(metadata.document().description()) + ","
                + "\"head\":" + HttpSupport.jsonString(DocumentRenderer.managedHead(metadata, view.mountPath())) + ","
                + "\"baseRevision\":" + snapshot.baseRevision() + ","
                + "\"revision\":" + snapshot.revision() + ","
                + "\"effects\":" + effectsJson(effects)
                + "}";
    }

    private String effectsJson(List<dev.roots.ClientEffect> effects) {
        var json = new StringBuilder("[");
        for (var index = 0; index < effects.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            var effect = effects.get(index);
            json.append("{\"type\":")
                    .append(HttpSupport.jsonString(effect.type().name()))
                    .append(",\"target\":")
                    .append(HttpSupport.jsonString(effect.target()))
                    .append(",\"value\":")
                    .append(HttpSupport.jsonString(effect.value()))
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static Response withResponseCookies(Response response, List<ResponseCookie> cookies) {
        var result = response;
        for (var cookie : cookies) {
            result = result.withCookie(cookie);
        }
        return result;
    }

    private Response notFound(String path) {
        var safePath = HtmlRenderer.escapeText(path);
        return Response.html(404, """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Not found · Roots</title></head><body><main><h1>404</h1><p>No Roots page matches <code>%s</code>.</p></main></body></html>
                """.formatted(safePath))
                .withHeader("Cache-Control", "no-store");
    }

    private Response errorResponse(Throwable exception, boolean json) {
        var message = config.development()
                ? exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage())
                : "Internal server error";
        if (json) {
            return Response.json(500, "{\"error\":" + HttpSupport.jsonString(message) + "}");
        }
        return Response.html(500, "<!doctype html><html><head><title>Roots error</title></head><body><h1>Roots error</h1><pre>"
                + HtmlRenderer.escapeText(message) + "</pre></body></html>");
    }

    private Response authorize(Request request, List<String> policies) throws Exception {
        for (var policy : policies) {
            var rejection = authorizePolicy(request, policy);
            if (rejection.isPresent()) {
                return rejection.orElseThrow().withHeader("Cache-Control", "no-store");
            }
        }
        return null;
    }

    private Optional<Response> authorizePolicy(Request request, String name) throws Exception {
        var policy = config.authorizationPolicies().get(name);
        if (policy == null) {
            throw new IllegalStateException("No authorization policy named '" + name + "' is registered");
        }
        return Objects.requireNonNull(policy.authorize(request), "Authorization policy result");
    }

    private Request authenticate(Request request) throws Exception {
        var identity = Objects.requireNonNull(
                config.authenticationProvider().authenticate(request),
                "Authentication provider result"
        );
        return request.withIdentity(identity);
    }

    private Response mappedErrorResponse(Request request, Throwable exception, boolean json) {
        if (request != null) {
            for (var mapper : config.exceptionMappers()) {
                try {
                    var mapped = Objects.requireNonNull(
                            mapper.map(request, exception),
                            "ExceptionMapper result"
                    );
                    if (mapped.isPresent()) {
                        return mapped.orElseThrow().withHeader("Cache-Control", "no-store");
                    }
                } catch (Throwable mapperFailure) {
                    exception.addSuppressed(mapperFailure);
                    LOG.log(System.Logger.Level.ERROR, "Roots exception mapper failed", mapperFailure);
                }
            }
        }
        if (exception instanceof ValidationException validation) {
            return validationResponse(validation);
        }
        LOG.log(System.Logger.Level.ERROR, "Unhandled Roots request failure", exception);
        var customError = customErrorPage(request, json, exception);
        if (customError != null) {
            return customError;
        }
        return errorResponse(exception, json);
    }

    private Response customErrorPage(Request request, boolean json, Throwable originalFailure) {
        if (request == null || json || config.development()
                || !request.method().equals("GET") && !request.method().equals("HEAD")
                || router.errorPage().isEmpty()
                || !pageDocumentRequest(request)) {
            return null;
        }
        try {
            return page(request, router.errorPage().orElseThrow(), 500);
        } catch (Throwable errorPageFailure) {
            originalFailure.addSuppressed(errorPageFailure);
            LOG.log(System.Logger.Level.ERROR,
                    "Roots ErrorPage failed; using the built-in error document", errorPageFailure);
            return null;
        }
    }

    private boolean pageDocumentRequest(Request request) {
        if (router.page(request.path()).isPresent()) {
            return true;
        }
        return router.notFound().isPresent() && pageLikeMissingRequest(
                request.path(),
                request.header("X-Roots-Request").orElse(null),
                request.header("Sec-Fetch-Dest").orElse(null),
                request.header("Accept").orElse(null)
        );
    }

    private Response validationResponse(ValidationException exception) {
        var fields = new StringBuilder("{");
        var entries = exception.fieldErrors().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for (var entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
            if (entryIndex > 0) {
                fields.append(',');
            }
            var entry = entries.get(entryIndex);
            fields.append(HttpSupport.jsonString(entry.getKey())).append(":").append('[');
            for (var errorIndex = 0; errorIndex < entry.getValue().size(); errorIndex++) {
                if (errorIndex > 0) {
                    fields.append(',');
                }
                fields.append(HttpSupport.jsonString(entry.getValue().get(errorIndex)));
            }
            fields.append(']');
        }
        fields.append('}');
        return Response.json(422, "{\"error\":" + HttpSupport.jsonString(exception.getMessage())
                        + ",\"fields\":" + fields + "}")
                .withHeader("Cache-Control", "no-store");
    }

    private void cleanup() {
        var cutoff = System.currentTimeMillis() - config.viewTimeout().toMillis();
        views.forEach((id, view) -> {
            if (view.expireIfIdle(cutoff) && views.remove(id, view)) {
                viewCapacity.release();
                ownership.release(id, view.sessionId());
            }
        });
        ownership.deleteExpired(Instant.now());
        sessions.cleanup();
    }

    private void maintenance() {
        try {
            cleanup();
        } catch (Throwable failure) {
            LOG.log(System.Logger.Level.WARNING, "Roots maintenance failed", failure);
        }
    }

    private Response sessionFreeResponse(TransportExchange exchange, String path) throws IOException {
        var method = exchange.method();
        var readable = method.equals("GET") || method.equals("HEAD");
        var prerendered = prerenders.response(
                method,
                path,
                exchange.requestHeader("If-None-Match"),
                exchange.mountPath()
        );
        if (prerendered != null) {
            return prerendered;
        }
        if (path.equals("/_roots/client.js")) {
            return readable
                    ? conditional(clientAsset, exchange.requestHeader("If-None-Match"))
                    : publicMethodNotAllowed(method);
        }
        if (path.equals("/_roots/client.css")) {
            return readable
                    ? conditional(clientStyle, exchange.requestHeader("If-None-Match"))
                    : publicMethodNotAllowed(method);
        }
        if (path.equals("/_roots/image")) {
            return readable ? optimizedImage(exchange) : publicMethodNotAllowed(method);
        }
        if (path.equals("/_roots/font.css")) {
            return readable ? fontStylesheet(exchange) : publicMethodNotAllowed(method);
        }
        if (config.development() && path.equals("/_roots/development.css")) {
            return readable
                    ? conditional(developmentStyle, exchange.requestHeader("If-None-Match"))
                    : publicMethodNotAllowed(method);
        }
        if (!config.development() && path.equals("/_roots/inspect")) {
            return readable ? notFound(path) : publicMethodNotAllowed(method);
        }
        if (isProtocolPath(path) || router.page(path).isPresent() || router.api(path).isPresent()) {
            return null;
        }
        var asset = asset(path);
        if (asset != null) {
            return readable
                    ? conditional(asset, exchange.requestHeader("If-None-Match"))
                    : publicMethodNotAllowed(method);
        }
        if (readable && router.notFound().isPresent() && pageLikeMissingRequest(exchange, path)) {
            return null;
        }
        return readable ? notFound(path) : publicMethodNotAllowed(method);
    }

    private static boolean pageLikeMissingRequest(TransportExchange exchange, String path) {
        return pageLikeMissingRequest(
                path,
                exchange.requestHeader("X-Roots-Request"),
                exchange.requestHeader("Sec-Fetch-Dest"),
                exchange.requestHeader("Accept")
        );
    }

    private static boolean pageLikeMissingRequest(
            String path,
            String rootsRequest,
            String fetchDestination,
            String accept
    ) {
        if (path.equals("/api") || path.startsWith("/api/")) {
            return false;
        }
        if (rootsRequest != null || "document".equalsIgnoreCase(fetchDestination)) {
            return true;
        }
        if (accept != null) {
            if (accept.toLowerCase(java.util.Locale.ROOT).contains("text/html")) {
                return true;
            }
            if (!accept.contains("*/*")) {
                return false;
            }
        }
        var segment = path.substring(path.lastIndexOf('/') + 1);
        return !segment.contains(".");
    }

    private Response invalidProtocolMethod(String method, String path) {
        if (!isProtocolPath(path)) {
            return null;
        }
        var expected = path.equals("/_roots/stream") || path.equals("/_roots/inspect") ? "GET" : "POST";
        if (method.equals(expected)) {
            return null;
        }
        return Response.methodNotAllowed(method)
                .withHeader("Allow", expected)
                .withHeader("Cache-Control", "no-store");
    }

    private Response missingProtocolSession(String path) {
        return switch (path) {
            case "/_roots/action" -> Response.json(409, "{\"error\":\"This live view has expired\"}")
                    .withHeader("Cache-Control", "no-store");
            case "/_roots/dispose" -> Response.noContent().withHeader("Cache-Control", "no-store");
            case "/_roots/stream", "/_roots/inspect" -> Response.text(404, "Live view not found")
                    .withHeader("Cache-Control", "no-store");
            default -> throw new IllegalArgumentException("Not a Roots protocol path: " + path);
        };
    }

    private Response publicMethodNotAllowed(String method) {
        return Response.methodNotAllowed(method)
                .withHeader("Allow", "GET, HEAD")
                .withHeader("Cache-Control", "no-store");
    }

    private Response optimizedImage(TransportExchange exchange) throws IOException {
        try {
            var optimized = imageOptimizer.optimize(HttpSupport.parameters(exchange.uri().getRawQuery()));
            var asset = cachedAsset(
                    optimized.contentType(),
                    config.development() ? "no-store" : "public, max-age=86400",
                    optimized.body()
            );
            return conditional(asset, exchange.requestHeader("If-None-Match"));
        } catch (ImageOptimizer.RequestFailure failure) {
            return Response.text(failure.status(), failure.getMessage()).withHeader("Cache-Control", "no-store");
        } catch (HttpSupport.BadRequestException failure) {
            return Response.text(400, "Malformed image query").withHeader("Cache-Control", "no-store");
        }
    }

    private Response fontStylesheet(TransportExchange exchange) throws IOException {
        try {
            var query = HttpSupport.parameters(exchange.uri().getRawQuery());
            if (!query.keySet().equals(java.util.Set.of("family", "src", "weight", "style", "display"))) {
                throw new IllegalArgumentException("Font stylesheets require family, src, weight, style, and display");
            }
            var font = new WebFont(
                    oneQuery(query, "family"),
                    oneQuery(query, "src"),
                    oneQuery(query, "weight"),
                    oneQuery(query, "style"),
                    oneQuery(query, "display"),
                    false
            );
            if (asset(font.source()) == null) {
                return Response.text(404, "Font asset not found").withHeader("Cache-Control", "no-store");
            }
            var key = AssetUrls.fontStylesheet(font);
            CachedAsset generated;
            synchronized (fontStylesheetCache) {
                generated = config.development() ? null : fontStylesheetCache.get(key);
                if (generated == null) {
                    var css = "@font-face{font-family:\"" + font.family()
                            + "\";src:url(\".." + font.source() + "\") format(\"" + fontFormat(font.source())
                            + "\");font-weight:" + font.weight() + ";font-style:" + font.style()
                            + ";font-display:" + font.display() + ";}";
                    generated = cachedAsset(
                            "text/css; charset=utf-8",
                            config.development() ? "no-store" : "public, max-age=31536000, immutable",
                            css.getBytes(StandardCharsets.UTF_8)
                    );
                    if (!config.development()) {
                        fontStylesheetCache.put(key, generated);
                        while (fontStylesheetCache.size() > 128) {
                            fontStylesheetCache.remove(fontStylesheetCache.keySet().iterator().next());
                        }
                    }
                }
            }
            return conditional(generated, exchange.requestHeader("If-None-Match"));
        } catch (IllegalArgumentException failure) {
            return Response.text(400, failure.getMessage()).withHeader("Cache-Control", "no-store");
        }
    }

    private static String oneQuery(Map<String, List<String>> query, String name) {
        var values = query.get(name);
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException("Font parameter '" + name + "' must appear exactly once");
        }
        return values.getFirst();
    }

    private static String fontFormat(String source) {
        return switch (extension(source)) {
            case "woff2" -> "woff2";
            case "woff" -> "woff";
            case "ttf" -> "truetype";
            case "otf" -> "opentype";
            default -> throw new IllegalArgumentException("Unsupported font type");
        };
    }

    private static Response conditional(CachedAsset asset, String ifNoneMatch) {
        if (ifNoneMatch == null || !matchesEtag(ifNoneMatch, asset.etag())) {
            return asset.response();
        }
        return new Response(304, asset.response().headers(), new byte[0]);
    }

    private static boolean matchesEtag(String header, String etag) {
        for (var candidate : header.split(",")) {
            var normalized = candidate.trim();
            if (normalized.equals("*")) {
                return true;
            }
            if (normalized.startsWith("W/")) {
                normalized = normalized.substring(2).trim();
            }
            if (normalized.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    private static CachedAsset cachedAsset(String contentType, String cacheControl, byte[] body) {
        var etag = etag(body);
        return new CachedAsset(new Response(200, Map.of(
                "Content-Type", List.of(contentType),
                "Cache-Control", List.of(cacheControl),
                "ETag", List.of(etag)
        ), body), etag);
    }

    private static String etag(byte[] body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(body);
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static boolean isProtocolPath(String path) {
        return path.equals("/_roots/action")
                || path.equals("/_roots/dispose")
                || path.equals("/_roots/stream")
                || path.equals("/_roots/inspect");
    }

    static boolean validAssetPath(String path) {
        if (!path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0) {
            return false;
        }
        for (var segment : path.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private Response health(TransportExchange exchange) {
        if (!exchange.method().equals("GET") && !exchange.method().equals("HEAD")) {
            return Response.methodNotAllowed(exchange.method())
                    .withHeader("Allow", "GET, HEAD")
                    .withHeader("Cache-Control", "no-store");
        }
        var ready = runtimeStarted.get() && acceptingRequests.get();
        return Response.json(ready ? 200 : 503, "{\"status\":"
                        + HttpSupport.jsonString(ready ? "UP" : "DRAINING")
                        + ",\"ready\":" + ready
                        + ",\"node\":" + HttpSupport.jsonString(ownership.localNodeId())
                        + "}")
                .withHeader("Cache-Control", "no-store");
    }

    private Response unavailable(String message) {
        return Response.json(503, "{\"error\":" + HttpSupport.jsonString(message) + "}")
                .withHeader("Cache-Control", "no-store")
                .withHeader("Retry-After", "1");
    }

    private static Response protocolMismatch() {
        return Response.json(409, "{\"error\":\"Roots protocol version mismatch\",\"reload\":true,\"protocol\":"
                        + HttpSupport.jsonString(Protocol.VERSION) + "}")
                .withHeader("Cache-Control", "no-store");
    }

    private void send(TransportExchange exchange, Response response, boolean head) {
        try {
            var outgoing = isProtocolPath(exchange.uri().getRawPath())
                    ? response.withHeader(Protocol.RESPONSE_HEADER, Protocol.VERSION)
                            .withHeader("X-Roots-Node", ownership.localNodeId())
                    : response;
            HttpSupport.send(exchange, secureHtml(outgoing), head);
        } catch (IOException exception) {
            LOG.log(System.Logger.Level.WARNING, "Could not write HTTP response", exception);
            exchange.close();
        }
    }

    private Response secureHtml(Response response) {
        var html = response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("Content-Type"))
                .flatMap(entry -> entry.getValue().stream())
                .anyMatch(value -> value.regionMatches(true, 0, "text/html", 0, "text/html".length()));
        return html
                ? response.withHeader("Content-Security-Policy", config.contentSecurityPolicy())
                : response;
    }

    private boolean removeView(String id, LiveView view) {
        if (!views.remove(id, view)) {
            return false;
        }
        viewCapacity.release();
        try {
            view.close();
        } finally {
            ownership.release(id, view.sessionId());
        }
        return true;
    }

    private void detachView(String id, LiveView view, List<Thread> cleaners) {
        if (!views.remove(id, view)) {
            return;
        }
        viewCapacity.release();
        cleaners.add(Thread.startVirtualThread(() -> {
            try {
                view.close();
            } finally {
                ownership.release(id, view.sessionId());
            }
        }));
    }

    private boolean renewOwnership(String viewId) {
        var now = Instant.now();
        var view = views.get(viewId);
        return view != null && ownership.renew(viewId, view.sessionId(), now, now.plus(config.viewTimeout()));
    }

    private Response missingView(String viewId, Session session) {
        var owner = ownership.find(viewId, Instant.now()).orElse(null);
        if (owner != null
                && owner.sessionId().equals(session.id())
                && !owner.nodeId().equals(ownership.localNodeId())) {
            return wrongNode(owner.nodeId());
        }
        if (owner != null && owner.nodeId().equals(ownership.localNodeId())) {
            ownership.release(viewId, owner.sessionId());
        }
        return Response.json(409, "{\"error\":\"This live view has expired\"}")
                .withHeader("Cache-Control", "no-store");
    }

    private static Response wrongNode(String nodeId) {
        return Response.json(409, "{\"error\":\"This live view belongs to another node\",\"retry\":true,"
                        + "\"owner\":" + HttpSupport.jsonString(nodeId) + "}")
                .withHeader("Cache-Control", "no-store")
                .withHeader("X-Roots-Owner", nodeId);
    }

    private void awaitCleaners(List<Thread> cleaners, long deadline) {
        for (var cleaner : cleaners) {
            var remaining = remainingUntil(deadline);
            if (remaining <= 0) {
                cleaner.interrupt();
                continue;
            }
            try {
                if (!cleaner.join(Duration.ofNanos(remaining))) {
                    cleaner.interrupt();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                cleaner.interrupt();
                return;
            }
        }
    }

    private void awaitRequests(long deadline) {
        synchronized (requestCompletion) {
            while (activeRequests.get() > 0) {
                var remaining = remainingUntil(deadline);
                if (remaining <= 0) {
                    return;
                }
                try {
                    var millis = remaining / 1_000_000;
                    var nanos = (int) (remaining % 1_000_000);
                    requestCompletion.wait(millis, nanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static long deadline(Duration timeout) {
        try {
            return Math.addExact(System.nanoTime(), timeout.toNanos());
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long remainingUntil(long deadline) {
        return deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
    }

    private static String first(Map<String, List<String>> values, String name) {
        return values.getOrDefault(name, List.of()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing Roots protocol value " + name));
    }

    private static void requireProtocol(String version) {
        if (!Protocol.matches(version)) {
            throw new ProtocolMismatchException();
        }
    }

    private static void protocolReload(TransportExchange exchange) throws IOException {
        exchange.responseHeader("Content-Type", "text/event-stream; charset=utf-8");
        exchange.responseHeader("Cache-Control", "no-store");
        exchange.responseHeader(Protocol.RESPONSE_HEADER, Protocol.VERSION);
        exchange.sendResponseHeaders(200, 0);
        try (var output = exchange.responseBody()) {
            output.write(("event: reload\ndata: {\"protocol\":"
                    + HttpSupport.jsonString(Protocol.VERSION) + "}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static BrowserEvent browserEvent(Map<String, List<String>> values, String eventType) {
        return new BrowserEvent(
                BrowserEvent.Type.fromWireName(eventType),
                optionalProtocolValue(values, "_event_key"),
                optionalProtocolValue(values, "_event_code"),
                protocolBoolean(values, "_event_alt"),
                protocolBoolean(values, "_event_control"),
                protocolBoolean(values, "_event_meta"),
                protocolBoolean(values, "_event_shift"),
                protocolInteger(values, "_event_button"),
                protocolInteger(values, "_event_client_x"),
                protocolInteger(values, "_event_client_y")
        );
    }

    private static Optional<String> optionalProtocolValue(Map<String, List<String>> values, String name) {
        var entries = values.get(name);
        if (entries == null) {
            return Optional.empty();
        }
        if (entries.size() != 1) {
            throw new IllegalArgumentException("Duplicate Roots protocol value " + name);
        }
        return Optional.of(entries.getFirst());
    }

    private static boolean protocolBoolean(Map<String, List<String>> values, String name) {
        return optionalProtocolValue(values, name)
                .map(value -> switch (value) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> throw new IllegalArgumentException("Invalid Roots protocol boolean " + name);
                })
                .orElse(false);
    }

    private static OptionalInt protocolInteger(Map<String, List<String>> values, String name) {
        var value = optionalProtocolValue(values, name);
        if (value.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(value.orElseThrow()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid Roots protocol integer " + name, exception);
        }
    }

    private static boolean acceptsJson(TransportExchange exchange) {
        var accept = exchange.requestHeader("Accept");
        return accept != null && accept.contains("application/json");
    }

    static String extension(String path) {
        var index = path.lastIndexOf('.');
        return index < 0 ? "" : path.substring(index + 1).toLowerCase();
    }

    private record CachedAsset(Response response, String etag) {
    }

    private static final class ProtocolMismatchException extends IllegalArgumentException {
        private ProtocolMismatchException() {
            super("Roots protocol version mismatch");
        }
    }
}
