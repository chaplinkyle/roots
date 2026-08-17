package dev.roots.servlet;

import dev.roots.RootsConfig;
import dev.roots.RootsCache;
import dev.roots.RuntimeSnapshot;
import dev.roots.internal.RootsServer;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Jakarta Servlet 6.1 adapter for a Roots application.
 * Map this servlet to {@code /*} and enable asynchronous support so live SSE streams
 * run on virtual threads instead of occupying container request threads.
 */
public final class RootsServlet extends HttpServlet {
    private static final System.Logger LOG = System.getLogger(RootsServlet.class.getName());

    /** Required no-argument constructor for descriptor and reflective container registration. */
    public RootsServlet() {
        this(null, Duration.ofSeconds(30), ServletIdentityResolver.containerPrincipal());
    }

    /** Creates a programmatically configured servlet.
     * @param config Roots application configuration */
    public RootsServlet(RootsConfig config) {
        this(config, Duration.ofSeconds(30), ServletIdentityResolver.containerPrincipal());
    }

    /** Creates a programmatically configured servlet.
     * @param config Roots application configuration
     * @param shutdownTimeout maximum graceful shutdown wait */
    public RootsServlet(RootsConfig config, Duration shutdownTimeout) {
        this(config, shutdownTimeout, ServletIdentityResolver.containerPrincipal());
    }

    /** Creates a programmatically configured servlet with custom identity resolution.
     * @param config Roots application configuration
     * @param shutdownTimeout maximum graceful shutdown wait
     * @param identityResolver resolver invoked on the container request thread
     */
    public RootsServlet(
            RootsConfig config,
            Duration shutdownTimeout,
            ServletIdentityResolver identityResolver
    ) {
        configured = config;
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("Shutdown timeout must not be negative");
        }
    }

    /** Programmatic configuration, or {@code null} when servlet init parameters own configuration. */
    private final RootsConfig configured;
    /** Maximum time allowed for runtime drain during servlet destruction. */
    private final Duration shutdownTimeout;
    /** Resolves immutable identity data before asynchronous Servlet dispatch. */
    private final ServletIdentityResolver identityResolver;
    /** Listener-independent Roots runtime owned by this servlet lifecycle. */
    private volatile RootsServer runtime;
    /** Final runtime counters retained after container destruction. */
    private volatile RuntimeSnapshot stoppedSnapshot;

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        super.init(servletConfig);
        try {
            var config = configured == null ? configuration(servletConfig) : configured;
            var starting = new RootsServer(config);
            starting.startRuntime();
            runtime = starting;
        } catch (RuntimeException | LinkageError failure) {
            throw new ServletException("Could not initialize Roots", failure);
        }
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, java.io.IOException {
        var running = runtime;
        if (running == null) {
            response.sendError(503, "Roots runtime is not running");
            return;
        }
        var identity = Objects.requireNonNull(identityResolver.resolve(request), "identityResolver result");
        var path = ServletTransportExchange.applicationPath(request);
        var streaming = "/_roots/stream".equals(path) || "/_roots/development".equals(path);
        if (!streaming) {
            running.handleTransport(new ServletTransportExchange(request, response, null, identity));
            return;
        }
        if (!request.isAsyncSupported()) {
            response.sendError(500, "Roots SSE requires async-supported servlet mapping");
            return;
        }
        var asynchronous = request.startAsync(request, response);
        asynchronous.setTimeout(0);
        var exchange = new ServletTransportExchange(
                (HttpServletRequest) asynchronous.getRequest(),
                (HttpServletResponse) asynchronous.getResponse(),
                asynchronous,
                identity
        );
        Thread.startVirtualThread(() -> handleAsynchronously(running, exchange, response));
    }

    /** Returns current transport-independent runtime counters.
     * @return runtime snapshot */
    public RuntimeSnapshot runtimeSnapshot() {
        var running = runtime;
        if (running != null) {
            return running.runtimeSnapshot();
        }
        var stopped = stoppedSnapshot;
        if (stopped != null) {
            return stopped;
        }
        throw new IllegalStateException("Roots servlet is not initialized");
    }

    /** Returns discovered route descriptions.
     * @return routes */
    public List<String> routes() {
        return requireRuntime().routes();
    }

    /** Returns the application cache.
     * @return cache */
    public RootsCache cache() {
        return requireRuntime().cache();
    }

    /** Returns the live-view affinity identifier for this servlet runtime.
     * @return node identifier */
    public String nodeId() {
        return requireRuntime().nodeId();
    }

    @Override
    public void destroy() {
        var stopping = runtime;
        if (stopping != null) {
            try {
                stopping.closeGracefully(shutdownTimeout);
            } finally {
                stoppedSnapshot = stopping.runtimeSnapshot();
                runtime = null;
            }
        }
        super.destroy();
    }

    private RootsServer requireRuntime() {
        var running = runtime;
        if (running == null) {
            throw new IllegalStateException("Roots servlet is not initialized");
        }
        return running;
    }

    private static void handleAsynchronously(
            RootsServer running,
            ServletTransportExchange exchange,
            HttpServletResponse response
    ) {
        try {
            running.handleTransport(exchange);
        } catch (Throwable failure) {
            LOG.log(System.Logger.Level.ERROR, "Unhandled Roots asynchronous transport failure", failure);
            if (!response.isCommitted()) {
                try {
                    response.sendError(500, "Roots asynchronous request failed");
                } catch (java.io.IOException ignored) {
                    // Completing the async context remains mandatory after a disconnected client.
                }
            }
            exchange.close();
        }
    }

    private static RootsConfig configuration(ServletConfig servletConfig) {
        var applicationName = servletConfig.getInitParameter("roots.applicationClass");
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalArgumentException(
                    "No RootsConfig was supplied and init parameter roots.applicationClass is missing"
            );
        }
        try {
            var loader = Thread.currentThread().getContextClassLoader();
            var applicationClass = Class.forName(applicationName, true, loader);
            var builder = RootsConfig.forApplication(applicationClass);
            var development = servletConfig.getInitParameter("roots.development");
            if (development != null) {
                if (!development.equalsIgnoreCase("true") && !development.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("roots.development must be true or false");
                }
                builder.development(Boolean.parseBoolean(development));
            }
            return builder.build();
        } catch (ClassNotFoundException exception) {
            throw new IllegalArgumentException("Roots application class was not found: " + applicationName, exception);
        }
    }
}
