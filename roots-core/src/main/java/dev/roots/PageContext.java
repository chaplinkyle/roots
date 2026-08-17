package dev.roots;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Route, session, cache, and tree-local context for a page render. */
public final class PageContext {
    private final String path;
    private final Map<String, String> parameters;
    private final Map<String, List<String>> query;
    private volatile Map<String, String> cookies = Map.of();
    private final Session session;
    private final RootsCache cache;
    private final Optional<AuthenticatedIdentity> identity;
    private final String mountPath;
    private volatile TraceContext traceContext;
    private volatile ClientConnection connection;
    private final Map<ContextKey<?>, Object> contextValues = new ConcurrentHashMap<>();
    private volatile Consumer<Runnable> updater;

    /** Creates a page context with caching disabled.
     * @param path logical route path
     * @param parameters route parameters
     * @param query repeated query values
     * @param session current session */
    public PageContext(
            String path,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Session session
    ) {
        this(path, parameters, query, session, RootsCache.disabled(), Optional.empty(), TraceContext.create(), "");
    }

    /** Creates a page context.
     * @param path logical route path
     * @param parameters route parameters
     * @param query repeated query values
     * @param session current session
     * @param cache application cache */
    public PageContext(
            String path,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Session session,
            RootsCache cache
    ) {
        this(path, parameters, query, session, cache, Optional.empty(), TraceContext.create(), "");
    }

    /** Creates a page context with transport authentication.
     * @param path logical route path
     * @param parameters route parameters
     * @param query repeated query values
     * @param session current session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous */
    public PageContext(
            String path,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity
    ) {
        this(path, parameters, query, session, cache, identity, TraceContext.create(), "");
    }

    /** Creates a page context with transport authentication and trace correlation.
     * @param path logical route path
     * @param parameters route parameters
     * @param query repeated query values
     * @param session current session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous
     * @param traceContext trace identifiers for the render trigger
     */
    public PageContext(
            String path,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity,
            TraceContext traceContext
    ) {
        this(path, parameters, query, session, cache, identity, traceContext, "");
    }

    /** Creates a page context with deployment-path correlation.
     * @param path logical route path
     * @param parameters route parameters
     * @param query repeated query values
     * @param session current session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous
     * @param traceContext trace identifiers for the render trigger
     * @param mountPath external path prefix, or empty at the origin root
     */
    public PageContext(
            String path,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity,
            TraceContext traceContext,
            String mountPath
    ) {
        this(path, parameters, query, session, cache, identity, traceContext, mountPath,
                ClientConnection.unknown("http", "localhost"));
    }

    /** Creates a page context with deployment and client-connection details.
     * @param path logical route path
     * @param parameters route parameters
     * @param query repeated query values
     * @param session current session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous
     * @param traceContext trace identifiers for the render trigger
     * @param mountPath external path prefix, or empty at the origin root
     * @param connection trusted client and public-origin details
     */
    public PageContext(
            String path,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity,
            TraceContext traceContext,
            String mountPath,
            ClientConnection connection
    ) {
        this.path = path;
        this.parameters = Map.copyOf(parameters);
        var copiedQuery = new LinkedHashMap<String, List<String>>();
        query.forEach((key, values) -> copiedQuery.put(key, List.copyOf(values)));
        this.query = Map.copyOf(copiedQuery);
        this.session = Objects.requireNonNull(session, "session");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.traceContext = Objects.requireNonNull(traceContext, "traceContext");
        this.mountPath = requireMountPath(mountPath);
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** Returns the logical route path.
     * @return path */
    public String path() {
        return path;
    }

    /** Requires a route parameter.
     * @param name parameter name
     * @return parameter value */
    public String parameter(String name) {
        var value = parameters.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Unknown route parameter: " + name);
        }
        return value;
    }

    /** Returns all route parameters.
     * @return immutable parameters */
    public Map<String, String> parameters() {
        return parameters;
    }

    /** Finds the first query value.
     * @param name query parameter name
     * @return first value, if present */
    public Optional<String> query(String name) {
        return query.getOrDefault(name, List.of()).stream().findFirst();
    }

    /** Returns every query value for a name.
     * @param name query parameter name
     * @return immutable values */
    public List<String> queryValues(String name) {
        return query.getOrDefault(name, List.of());
    }

    /** Returns valid cookies from the request that triggered the current render.
     * The snapshot is replaced atomically before each action rerender.
     * @return immutable cookies by name */
    public Map<String, String> cookies() {
        return cookies;
    }

    /** Finds a cookie from the request that triggered the current render.
     * @param name cookie name
     * @return cookie value, if present */
    public Optional<String> cookie(String name) {
        return Optional.ofNullable(cookies.get(CookieSyntax.requireName(name)));
    }

    /** Returns the application mount as a safe cookie path.
     * @return {@code /} at the origin root, otherwise the mount path */
    public String cookiePath() {
        return mountPath.isEmpty() ? "/" : mountPath;
    }

    /** Returns the current session.
     * @return session */
    public Session session() {
        return session;
    }

    /** Returns the application-scoped data cache.
     * @return cache */
    public RootsCache cache() {
        return cache;
    }

    /** Returns the authenticated identity that owns this live view.
     * @return authenticated identity, or empty for anonymous */
    public Optional<AuthenticatedIdentity> identity() {
        return identity;
    }

    /** Returns trace identifiers for the request that triggered the current render.
     * @return current render trace context
     */
    public TraceContext traceContext() {
        return traceContext;
    }

    /** Returns trusted client and public-origin details for the current render trigger.
     * @return current connection details
     */
    public ClientConnection connection() {
        return connection;
    }

    /** Returns the external path prefix at which this application is mounted.
     * @return empty string at the origin root, otherwise a leading-slash path
     */
    public String mountPath() {
        return mountPath;
    }

    /** Resolves an application-root-relative path beneath the current mount.
     * Absolute URLs, protocol-relative URLs, fragments, and relative paths are unchanged.
     * @param applicationPath application path or URL
     * @return externally reachable path
     */
    public String url(String applicationPath) {
        Objects.requireNonNull(applicationPath, "applicationPath");
        if (mountPath.isEmpty() || !applicationPath.startsWith("/") || applicationPath.startsWith("//")
                || applicationPath.equals(mountPath) || applicationPath.startsWith(mountPath + "/")) {
            return applicationPath;
        }
        return mountPath + applicationPath;
    }

    /**
     * Updates correlation before a framework-triggered live rerender.
     * Application code normally reads {@link #traceContext()} and does not call this method.
     *
     * @param traceContext replacement trace context
     */
    public void updateTraceContext(TraceContext traceContext) {
        this.traceContext = Objects.requireNonNull(traceContext, "traceContext");
    }

    /** Updates transport details before a framework-triggered live rerender.
     * Application code normally reads {@link #connection()} and does not call this method.
     * @param connection replacement trusted connection details
     */
    public void updateConnection(ClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** Updates cookies before a framework-triggered live rerender.
     * Application code normally reads {@link #cookies()} and does not call this method.
     * @param cookies replacement validated request cookies */
    public void updateCookies(Map<String, String> cookies) {
        this.cookies = CookieSyntax.copy(cookies);
    }

    private static String requireMountPath(String value) {
        Objects.requireNonNull(value, "mountPath");
        if (!value.isEmpty() && (!value.matches("/[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*")
                || java.util.Arrays.stream(value.substring(1).split("/"))
                .anyMatch(segment -> segment.equals(".") || segment.equals("..")))) {
            throw new IllegalArgumentException("Mount path must be empty or a normalized absolute path");
        }
        return value;
    }

    /** Provides or removes a typed tree-local context value.
     * @param <T> value type
     * @param key context key
     * @param value value, or {@code null} to remove */
    public <T> void provide(ContextKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        if (value == null) {
            contextValues.remove(key);
        } else {
            contextValues.put(key, value);
        }
    }

    /** Resolves a typed tree-local context value.
     * @param <T> value type
     * @param key context key
     * @return provided or default value */
    public <T> T context(ContextKey<T> key) {
        Objects.requireNonNull(key, "key");
        var value = contextValues.get(key);
        if (value == null) {
            return key.defaultValue();
        }
        @SuppressWarnings("unchecked")
        var typed = (T) value;
        return typed;
    }

    /** Safely mutates a mounted live view from a background or virtual thread.
     * @param mutation state mutation */
    public void update(Runnable mutation) {
        var currentUpdater = updater;
        if (currentUpdater == null) {
            throw new IllegalStateException("This page is not attached to a live browser view");
        }
        currentUpdater.accept(mutation);
    }

    /** Attaches the one-time live-view update dispatcher.
     * @param updater update dispatcher */
    public void attachUpdater(Consumer<Runnable> updater) {
        if (this.updater != null) {
            throw new IllegalStateException("A PageContext can only be attached once");
        }
        this.updater = updater;
    }

    boolean attachedToView() {
        return updater != null;
    }
}
