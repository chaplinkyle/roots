package dev.roots.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.roots.ApiRoute;
import dev.roots.Metadata;
import dev.roots.Request;
import dev.roots.Response;
import dev.roots.RootsConfig;
import dev.roots.Session;
import dev.roots.html.HtmlRenderer;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class RootsServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RootsServer.class.getName());

    private final RootsConfig config;
    private final ConventionRouter router;
    private final SessionStore sessions;
    private final Map<String, LiveView> views = new ConcurrentHashMap<>();
    private final AtomicLong requestCount = new AtomicLong();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;

    public RootsServer(RootsConfig config) {
        this.config = config;
        router = ConventionRouter.discover(config);
        sessions = new SessionStore(Duration.ofHours(8));
    }

    public void start() {
        if (server != null) {
            throw new IllegalStateException("Roots server already started");
        }
        try {
            server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start Roots on " + config.host() + ":" + config.port(), exception);
        }
    }

    public URI uri() {
        if (server == null) {
            throw new IllegalStateException("Roots server has not started");
        }
        var visibleHost = config.host().equals("0.0.0.0") ? "localhost" : config.host();
        return URI.create("http://" + visibleHost + ":" + server.getAddress().getPort());
    }

    public List<String> routes() {
        return router.routes();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        executor.shutdownNow();
        views.values().forEach(LiveView::close);
        views.clear();
    }

    private void handle(HttpExchange exchange) {
        var resolvedSession = sessions.resolve(exchange);
        if (exchange.getRequestURI().getRawPath().equals("/_roots/stream")) {
            if (resolvedSession.isNew()) {
                exchange.getResponseHeaders().set("Set-Cookie", resolvedSession.setCookieHeader());
            }
            stream(exchange, resolvedSession.session());
            return;
        }
        Response response;
        try {
            response = dispatch(exchange, resolvedSession.session());
        } catch (HttpSupport.RequestTooLargeException exception) {
            response = Response.text(413, "Request body is too large");
        } catch (Throwable exception) {
            LOG.log(System.Logger.Level.ERROR, "Unhandled Roots request failure", exception);
            response = errorResponse(exception, acceptsJson(exchange));
        }

        if (resolvedSession.isNew()) {
            response = response.withHeader("Set-Cookie", resolvedSession.setCookieHeader());
        }
        try {
            HttpSupport.send(exchange, response, exchange.getRequestMethod().equals("HEAD"));
        } catch (IOException exception) {
            LOG.log(System.Logger.Level.WARNING, "Could not write HTTP response", exception);
            exchange.close();
        }

        if ((requestCount.incrementAndGet() & 127) == 0) {
            cleanup();
        }
    }

    private Response dispatch(HttpExchange exchange, Session session) throws Exception {
        var rawPath = exchange.getRequestURI().getRawPath();
        if (rawPath.equals("/_roots/client.js")) {
            return javascript();
        }
        if (rawPath.equals("/_roots/action")) {
            return action(exchange, session);
        }
        var api = router.api(rawPath);
        if (api.isPresent()) {
            return api(exchange, session, api.orElseThrow());
        }
        if (exchange.getRequestMethod().equals("GET") || exchange.getRequestMethod().equals("HEAD")) {
            var page = router.page(rawPath);
            if (page.isPresent()) {
                return page(exchange, session, page.orElseThrow());
            }
            var asset = asset(rawPath);
            if (asset != null) {
                return asset;
            }
            return notFound(rawPath);
        }
        return Response.methodNotAllowed(exchange.getRequestMethod())
                .withHeader("Allow", "GET, HEAD");
    }

    private Response page(HttpExchange exchange, Session session, ConventionRouter.PageMatch match) {
        var query = HttpSupport.parameters(exchange.getRequestURI().getRawQuery());
        var view = new LiveView(match, exchange.getRequestURI().getPath(), query, session);
        views.put(view.id(), view);
        var snapshot = view.snapshot();
        return document(200, view, snapshot)
                .withHeader("Cache-Control", "no-store")
                .withHeader("Content-Security-Policy", csp());
    }

    private Response action(HttpExchange exchange, Session session) throws Exception {
        if (!exchange.getRequestMethod().equals("POST")) {
            return Response.methodNotAllowed(exchange.getRequestMethod()).withHeader("Allow", "POST");
        }
        var request = HttpSupport.request(exchange, Map.of(), session);
        var values = request.form();
        var viewId = first(values, "_view");
        var csrf = first(values, "_csrf");
        var actionName = first(values, "_action");
        var eventType = first(values, "_event");
        var view = views.get(viewId);
        if (view == null
                || !view.sessionId().equals(session.id())
                || !MessageDigest.isEqual(
                        view.csrf().getBytes(StandardCharsets.UTF_8),
                        csrf.getBytes(StandardCharsets.UTF_8))) {
            return Response.json(409, "{\"error\":\"This live view has expired\"}");
        }
        var publicValues = new LinkedHashMap<String, List<String>>();
        values.forEach((name, value) -> {
            if (!name.startsWith("_")) {
                publicValues.put(name, value);
            }
        });
        try {
            var result = view.invoke(actionName, eventType, Map.copyOf(publicValues), session);
            if (result.redirect() != null) {
                return Response.json(200, "{\"redirect\":" + HttpSupport.jsonString(result.redirect())
                                + ",\"effects\":" + effectsJson(result.effects()) + "}")
                        .withHeader("Cache-Control", "no-store");
            }
            return Response.json(200, patchJson(result.snapshot(), result.effects()))
                    .withHeader("Cache-Control", "no-store");
        } catch (LiveView.StaleViewException exception) {
            return Response.json(409, "{\"error\":\"This live view has expired\"}");
        } catch (IllegalArgumentException exception) {
            return Response.json(422, "{\"error\":" + HttpSupport.jsonString(exception.getMessage()) + "}");
        }
    }

    private Response api(HttpExchange exchange, Session session, ConventionRouter.ApiMatch match) throws Exception {
        var request = HttpSupport.request(exchange, match.parameters(), session);
        var route = instantiate(match.route().type());
        return route.handle(request).withHeader("Cache-Control", "no-store");
    }

    private Response javascript() {
        return Response.of(200, "text/javascript; charset=utf-8", ClientRuntime.SOURCE)
                .withHeader("Cache-Control", config.development() ? "no-store" : "public, max-age=3600");
    }

    private void stream(HttpExchange exchange, Session session) {
        try {
            if (!exchange.getRequestMethod().equals("GET")) {
                HttpSupport.send(exchange, Response.methodNotAllowed(exchange.getRequestMethod()).withHeader("Allow", "GET"), false);
                return;
            }
            var query = HttpSupport.parameters(exchange.getRequestURI().getRawQuery());
            var viewId = first(query, "view");
            var csrf = first(query, "csrf");
            var view = views.get(viewId);
            if (view == null
                    || !view.sessionId().equals(session.id())
                    || !MessageDigest.isEqual(view.csrf().getBytes(StandardCharsets.UTF_8), csrf.getBytes(StandardCharsets.UTF_8))) {
                HttpSupport.send(exchange, Response.text(404, "Live view not found"), false);
                return;
            }
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-store");
            headers.set("Connection", "keep-alive");
            headers.set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                while (views.get(viewId) == view) {
                    var snapshot = view.nextPatch(Duration.ofSeconds(20));
                    var message = snapshot == null
                            ? ": heartbeat\n\n"
                            : "event: patch\ndata: " + patchJson(snapshot) + "\n\n";
                    output.write(message.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            }
        } catch (IOException exception) {
            // Browser navigation and tab closure normally end an SSE connection this way.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "Live update stream failed", exception);
            exchange.close();
        }
    }

    private Response asset(String path) throws IOException {
        if (path.contains("..") || path.indexOf('\\') >= 0) {
            return null;
        }
        var resourceName = "public" + (path.equals("/") ? "/index.html" : path);
        try (var input = config.applicationClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                return null;
            }
            var contentType = switch (extension(path)) {
                case "css" -> "text/css; charset=utf-8";
                case "js" -> "text/javascript; charset=utf-8";
                case "json" -> "application/json; charset=utf-8";
                case "svg" -> "image/svg+xml";
                case "png" -> "image/png";
                case "jpg", "jpeg" -> "image/jpeg";
                case "webp" -> "image/webp";
                case "ico" -> "image/x-icon";
                default -> "application/octet-stream";
            };
            return new Response(200, Map.of(
                    "Content-Type", List.of(contentType),
                    "Cache-Control", List.of(config.development() ? "no-store" : "public, max-age=3600")
            ), input.readAllBytes());
        }
    }

    private Response document(int status, LiveView view, LiveView.Snapshot snapshot) {
        var metadata = snapshot.metadata();
        var styles = new StringBuilder();
        for (var stylesheet : metadata.stylesheets()) {
            styles.append("<link rel=\"stylesheet\" href=\"")
                    .append(HtmlRenderer.escapeAttribute(stylesheet))
                    .append("\">");
        }
        var body = """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta name="description" content="%s">
                  <title>%s</title>
                  %s
                  <script src="/_roots/client.js" defer></script>
                </head>
                <body>
                  <div id="roots" data-roots-view="%s" data-roots-csrf="%s" data-roots-revision="%d">%s</div>
                </body>
                </html>
                """.formatted(
                HtmlRenderer.escapeAttribute(metadata.description()),
                HtmlRenderer.escapeText(metadata.title()),
                styles,
                HtmlRenderer.escapeAttribute(view.id()),
                HtmlRenderer.escapeAttribute(view.csrf()),
                snapshot.revision(),
                snapshot.html()
        );
        return Response.html(status, body);
    }

    private Response patch(LiveView.Snapshot snapshot) {
        return Response.json(200, patchJson(snapshot));
    }

    private String patchJson(LiveView.Snapshot snapshot) {
        return patchJson(snapshot, List.of());
    }

    private String patchJson(LiveView.Snapshot snapshot, List<dev.roots.ClientEffect> effects) {
        var metadata = snapshot.metadata();
        return "{"
                + "\"html\":" + HttpSupport.jsonString(snapshot.html()) + ","
                + "\"title\":" + HttpSupport.jsonString(metadata.title()) + ","
                + "\"description\":" + HttpSupport.jsonString(metadata.description()) + ","
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

    private Response notFound(String path) {
        var safePath = HtmlRenderer.escapeText(path);
        return Response.html(404, """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Not found · Roots</title></head><body><main><h1>404</h1><p>No Roots page matches <code>%s</code>.</p></main></body></html>
                """.formatted(safePath))
                .withHeader("Content-Security-Policy", csp());
    }

    private Response errorResponse(Throwable exception, boolean json) {
        var message = config.development()
                ? exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage())
                : "Internal server error";
        if (json) {
            return Response.json(500, "{\"error\":" + HttpSupport.jsonString(message) + "}");
        }
        return Response.html(500, "<!doctype html><html><head><title>Roots error</title></head><body><h1>Roots error</h1><pre>"
                + HtmlRenderer.escapeText(message) + "</pre></body></html>")
                .withHeader("Content-Security-Policy", csp());
    }

    private void cleanup() {
        var cutoff = System.currentTimeMillis() - config.viewTimeout().toMillis();
        views.entrySet().removeIf(entry -> {
            if (entry.getValue().lastAccess() >= cutoff) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
        sessions.cleanup();
    }

    private static String first(Map<String, List<String>> values, String name) {
        return values.getOrDefault(name, List.of()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing Roots protocol value " + name));
    }

    private static <T> T instantiate(Class<? extends T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            if (!constructor.trySetAccessible()) {
                throw new IllegalStateException("Convention class needs an accessible no-argument constructor: " + type.getName());
            }
            return constructor.newInstance();
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Convention class needs a no-argument constructor: " + type.getName(), exception);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Could not create " + type.getName(), exception);
        }
    }

    private static boolean acceptsJson(HttpExchange exchange) {
        var accept = exchange.getRequestHeaders().getFirst("Accept");
        return accept != null && accept.contains("application/json");
    }

    private static String extension(String path) {
        var index = path.lastIndexOf('.');
        return index < 0 ? "" : path.substring(index + 1).toLowerCase();
    }

    private static String csp() {
        return "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                + "connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";
    }
}
