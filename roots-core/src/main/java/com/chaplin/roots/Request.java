package com.chaplin.roots;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable HTTP request exposed to middleware and API routes.
 *
 * Request bodies are repeatable and may spool to a request-scoped temporary file.
 * Roots closes that content after the synchronous middleware/route/action pipeline.
 */
public final class Request implements AutoCloseable {
    private final String method;
    private final String path;
    private final String transportPath;
    private final Map<String, String> parameters;
    private final Map<String, List<String>> query;
    private final Map<String, List<String>> headers;
    private final Map<String, String> cookies;
    private final Map<String, List<String>> form;
    private final Map<String, List<UploadedFile>> files;
    private final BinaryContent body;
    private final Session session;
    private final RootsCache cache;
    private final Optional<AuthenticatedIdentity> identity;
    private final TraceContext traceContext;
    private final String mountPath;
    private final ClientConnection connection;

    /** Creates a request from defensively copied in-memory body bytes.
     * @param method uppercase HTTP method
     * @param path logical application path
     * @param transportPath actual HTTP endpoint path
     * @param parameters route parameters
     * @param query repeated query values
     * @param headers repeated request headers
     * @param form repeated form values
     * @param files repeated multipart files
     * @param body raw request body
     * @param session request session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous
     * @param traceContext distributed trace identifiers
     * @param mountPath external application mount
     * @param connection trusted connection details */
    public Request(
            String method,
            String path,
            String transportPath,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Map<String, List<String>> headers,
            Map<String, List<String>> form,
            Map<String, List<UploadedFile>> files,
            byte[] body,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity,
            TraceContext traceContext,
            String mountPath,
            ClientConnection connection
    ) {
        this(method, path, transportPath, parameters, query, headers, form, files, BinaryContent.of(body), session,
                cache, identity, traceContext, mountPath, connection);
    }

    /** Creates a request from repeatable binary content.
     * @param method uppercase HTTP method
     * @param path logical application path
     * @param transportPath actual HTTP endpoint path
     * @param parameters route parameters
     * @param query repeated query values
     * @param headers repeated request headers
     * @param form repeated form values
     * @param files repeated multipart files
     * @param body repeatable raw request content
     * @param session request session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous
     * @param traceContext distributed trace identifiers
     * @param mountPath external application mount
     * @param connection trusted connection details */
    public Request(
            String method,
            String path,
            String transportPath,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Map<String, List<String>> headers,
            Map<String, List<String>> form,
            Map<String, List<UploadedFile>> files,
            BinaryContent body,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity,
            TraceContext traceContext,
            String mountPath,
            ClientConnection connection
    ) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(transportPath, "transportPath");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(cache, "cache");
        identity = Objects.requireNonNull(identity, "identity");
        traceContext = Objects.requireNonNull(traceContext, "traceContext");
        mountPath = requireMountPath(mountPath);
        connection = Objects.requireNonNull(connection, "connection");
        this.method = method.toUpperCase(Locale.ROOT);
        this.path = path;
        this.transportPath = transportPath;
        this.parameters = Map.copyOf(parameters);
        this.query = copyValues(query);
        this.headers = copyValues(headers);
        this.cookies = CookieSyntax.parse(this.headers);
        this.form = copyValues(form);
        this.files = copyFiles(files);
        this.body = Objects.requireNonNull(body, "body");
        this.session = session;
        this.cache = cache;
        this.identity = identity;
        this.traceContext = traceContext;
        this.mountPath = mountPath;
        this.connection = connection;
    }

    /** Preserves the request constructor from before client-connection resolution.
     * @param method uppercase HTTP method
     * @param path logical application path
     * @param transportPath actual HTTP endpoint path
     * @param parameters route parameters
     * @param query repeated query values
     * @param headers repeated request headers
     * @param form repeated form values
     * @param files repeated multipart files
     * @param body raw request body
     * @param session request session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous
     * @param traceContext distributed trace identifiers
     * @param mountPath external application mount
     */
    public Request(
            String method,
            String path,
            String transportPath,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Map<String, List<String>> headers,
            Map<String, List<String>> form,
            Map<String, List<UploadedFile>> files,
            byte[] body,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity,
            TraceContext traceContext,
            String mountPath
    ) {
        this(method, path, transportPath, parameters, query, headers, form, files, body, session, cache,
                identity, traceContext, mountPath, ClientConnection.unknown("http", "localhost"));
    }

    /** Preserves the request constructor from before external mount-path propagation.
     * @param method uppercase HTTP method
     * @param path logical application path
     * @param transportPath actual HTTP endpoint path
     * @param parameters route parameters
     * @param query repeated query values
     * @param headers repeated request headers
     * @param form repeated form values
     * @param files repeated multipart files
     * @param body raw request body
     * @param session request session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous
     * @param traceContext distributed trace identifiers
     */
    public Request(
            String method,
            String path,
            String transportPath,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Map<String, List<String>> headers,
            Map<String, List<String>> form,
            Map<String, List<UploadedFile>> files,
            byte[] body,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity,
            TraceContext traceContext
    ) {
        this(method, path, transportPath, parameters, query, headers, form, files, body, session, cache,
                identity, traceContext, "", ClientConnection.unknown("http", "localhost"));
    }

    /** Preserves the request constructor from before trace-context propagation.
     * @param method uppercase HTTP method
     * @param path logical application path
     * @param transportPath actual HTTP endpoint path
     * @param parameters route parameters
     * @param query repeated query values
     * @param headers repeated request headers
     * @param form repeated form values
     * @param files repeated multipart files
     * @param body raw request body
     * @param session request session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous
     */
    public Request(
            String method,
            String path,
            String transportPath,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Map<String, List<String>> headers,
            Map<String, List<String>> form,
            Map<String, List<UploadedFile>> files,
            byte[] body,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity
    ) {
        this(method, path, transportPath, parameters, query, headers, form, files, body, session, cache,
                identity, TraceContext.create());
    }

    /** Preserves the request constructor from before authenticated identity propagation.
     * @param method uppercase HTTP method
     * @param path logical application path
     * @param transportPath actual HTTP endpoint path
     * @param parameters route parameters
     * @param query repeated query values
     * @param headers repeated request headers
     * @param form repeated form values
     * @param files repeated multipart files
     * @param body raw request body
     * @param session request session
     * @param cache application cache */
    public Request(
            String method,
            String path,
            String transportPath,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Map<String, List<String>> headers,
            Map<String, List<String>> form,
            Map<String, List<UploadedFile>> files,
            byte[] body,
            Session session,
            RootsCache cache
    ) {
        this(method, path, transportPath, parameters, query, headers, form, files, body, session, cache,
                Optional.empty());
    }

    /** Preserves the request constructor from before application-scoped caching.
     * @param method uppercase HTTP method
     * @param path logical application path
     * @param transportPath actual HTTP endpoint path
     * @param parameters route parameters
     * @param query repeated query values
     * @param headers repeated request headers
     * @param form repeated form values
     * @param files repeated multipart files
     * @param body raw request body
     * @param session request session */
    public Request(
            String method,
            String path,
            String transportPath,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Map<String, List<String>> headers,
            Map<String, List<String>> form,
            Map<String, List<UploadedFile>> files,
            byte[] body,
            Session session
    ) {
        this(method, path, transportPath, parameters, query, headers, form, files, body, session,
                RootsCache.disabled());
    }

    /** Preserves the request constructor from before multipart file support.
     * @param method uppercase HTTP method
     * @param path logical application path
     * @param transportPath actual HTTP endpoint path
     * @param parameters route parameters
     * @param query repeated query values
     * @param headers repeated request headers
     * @param form repeated form values
     * @param body raw request body
     * @param session request session */
    public Request(
            String method,
            String path,
            String transportPath,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Map<String, List<String>> headers,
            Map<String, List<String>> form,
            byte[] body,
            Session session
    ) {
        this(method, path, transportPath, parameters, query, headers, form, Map.of(), body, session,
                RootsCache.disabled());
    }

    /** Preserves the original 0.1 constructor; ordinary requests use the same logical and transport path.
     * @param method uppercase HTTP method
     * @param path logical and transport path
     * @param parameters route parameters
     * @param query repeated query values
     * @param headers repeated request headers
     * @param form repeated form values
     * @param body raw request body
     * @param session request session */
    public Request(
            String method,
            String path,
            Map<String, String> parameters,
            Map<String, List<String>> query,
            Map<String, List<String>> headers,
            Map<String, List<String>> form,
            byte[] body,
            Session session
    ) {
        this(method, path, path, parameters, query, headers, form, Map.of(), body, session,
                RootsCache.disabled());
    }

    /** Returns the uppercase HTTP method.
     * @return method */
    public String method() { return method; }

    /** Returns the logical application path.
     * @return logical path */
    public String path() { return path; }

    /** Returns the actual transport endpoint path.
     * @return transport path */
    public String transportPath() { return transportPath; }

    /** Returns immutable route parameters.
     * @return route parameters */
    public Map<String, String> parameters() { return parameters; }

    /** Returns immutable repeated query values.
     * @return query values */
    public Map<String, List<String>> query() { return query; }

    /** Returns immutable repeated request headers.
     * @return headers */
    public Map<String, List<String>> headers() { return headers; }

    /** Returns parsed valid request cookies. The first occurrence of a duplicate name wins;
     * malformed cookie pairs are ignored without rejecting the request.
     * @return immutable cookies by name */
    public Map<String, String> cookies() { return cookies; }

    /** Finds a parsed request cookie.
     * @param name cookie name
     * @return cookie value, if present */
    public Optional<String> cookie(String name) {
        return Optional.ofNullable(cookies.get(CookieSyntax.requireName(name)));
    }

    /** Returns immutable repeated form values.
     * @return form values */
    public Map<String, List<String>> form() { return form; }

    /** Returns immutable repeated multipart files.
     * @return files */
    public Map<String, List<UploadedFile>> files() { return files; }

    /** Returns the request session.
     * @return session */
    public Session session() { return session; }

    /** Returns the application-scoped cache.
     * @return cache */
    public RootsCache cache() { return cache; }

    /** Returns the authenticated identity.
     * @return identity, or empty for anonymous */
    public Optional<AuthenticatedIdentity> identity() { return identity; }

    /** Returns distributed trace identifiers.
     * @return trace context */
    public TraceContext traceContext() { return traceContext; }

    /** Returns the external application mount path.
     * @return mount path */
    public String mountPath() { return mountPath; }

    /** Returns trusted client and public-origin details.
     * @return connection details */
    public ClientConnection connection() { return connection; }

    /** Returns a defensive copy of the raw request body.
     * @return copied body bytes */
    public byte[] body() {
        return body.readAllBytes();
    }

    /** Opens a repeatable stream for the raw request body.
     * @return body stream
     * @throws IOException when request-scoped content is closed or unreadable */
    public InputStream bodyStream() throws IOException {
        return body.openStream();
    }

    /** Returns the exact raw request-body byte count.
     * @return body size */
    public long bodySize() {
        return body.size();
    }

    /** Reports whether the raw request body is retained entirely in heap memory.
     * @return true for an in-memory body */
    public boolean bodyInMemory() {
        return body.inMemory();
    }

    /** Returns a copy with different route parameters.
     * @param parameters replacement route parameters
     * @return updated request */
    public Request withParameters(Map<String, String> parameters) {
        return new Request(method, path, transportPath, parameters, query, headers, form, files, body, session, cache,
                identity, traceContext, mountPath, connection);
    }

    /** Returns a copy with a different logical path.
     * @param path replacement logical path
     * @return updated request */
    public Request withLogicalPath(String path) {
        return new Request(method, path, transportPath, parameters, query, headers, form, files, body, session, cache,
                identity, traceContext, mountPath, connection);
    }

    /** Replaces route-facing context while preserving the underlying transport request.
     * @param path replacement logical path
     * @param parameters replacement route parameters
     * @param query replacement query values
     * @return updated request */
    public Request withLogicalRoute(
            String path,
            Map<String, String> parameters,
            Map<String, List<String>> query
    ) {
        return new Request(method, path, transportPath, parameters, query, headers, form, files, body, session, cache,
                identity, traceContext, mountPath, connection);
    }

    /** Returns a copy carrying the resolved authenticated identity.
     * @param identity replacement identity, or empty for anonymous
     * @return updated request */
    public Request withIdentity(Optional<AuthenticatedIdentity> identity) {
        var replacement = Objects.requireNonNull(identity, "identity");
        if (this.identity.equals(replacement)) {
            return this;
        }
        return new Request(method, path, transportPath, parameters, query, headers, form, files, body, session, cache,
                replacement, traceContext, mountPath, connection);
    }

    /** Finds the first request header value, case-insensitively.
     * @param name header name
     * @return first value, if present */
    public Optional<String> header(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
    }

    /** Finds the first query value.
     * @param name query parameter name
     * @return first value, if present */
    public Optional<String> queryValue(String name) {
        return query.getOrDefault(name, List.of()).stream().findFirst();
    }

    /** Finds the first submitted form value.
     * @param name form field name
     * @return first value, if present */
    public Optional<String> formValue(String name) {
        return form.getOrDefault(name, List.of()).stream().findFirst();
    }

    /** Returns all submitted values for a form field.
     * @param name form field name
     * @return immutable repeated values */
    public List<String> formValues(String name) {
        return form.getOrDefault(name, List.of());
    }

    /** Returns all submitted form values.
     * @return immutable repeated values keyed by field */
    public Map<String, List<String>> allFormValues() {
        return form;
    }

    /**
     * Finds the first uploaded file for a field.
     *
     * @param name multipart field name
     * @return the first uploaded file for the field
     */
    public Optional<UploadedFile> file(String name) {
        return files.getOrDefault(name, List.of()).stream().findFirst();
    }

    /**
     * Returns every uploaded file for a field.
     *
     * @param name multipart field name
     * @return immutable uploaded files for the field
     */
    public List<UploadedFile> files(String name) {
        return files.getOrDefault(name, List.of());
    }

    /** Returns every uploaded file.
     * @return immutable files keyed by multipart field name */
    public Map<String, List<UploadedFile>> allFiles() {
        return files;
    }

    /**
     * Converts submitted text fields and uploads into a validated Java record.
     *
     * @param recordType target record type
     * @param <T> record type
     * @return constructed, validated form record
     */
    public <T> T bind(Class<T> recordType) {
        return com.chaplin.roots.validation.FormBinder.bind(form, files, recordType);
    }

    /** Decodes the request body as UTF-8.
     * @return decoded request body */
    public String bodyText() {
        return new String(body(), StandardCharsets.UTF_8);
    }

    /** Releases request-scoped body and upload backing resources.
     * Roots invokes this after the synchronous request pipeline.
     * @throws UncheckedIOException when cleanup fails */
    @Override
    public void close() {
        try {
            body.close();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not release request content", exception);
        }
    }

    private static Map<String, List<String>> copyValues(Map<String, List<String>> source) {
        Objects.requireNonNull(source, "map");
        var copy = new LinkedHashMap<String, List<String>>();
        source.forEach((key, values) -> copy.put(
                Objects.requireNonNull(key, "map key"),
                List.copyOf(Objects.requireNonNull(values, "map values"))
        ));
        return Map.copyOf(copy);
    }

    private static Map<String, List<UploadedFile>> copyFiles(Map<String, List<UploadedFile>> source) {
        Objects.requireNonNull(source, "files");
        var copy = new LinkedHashMap<String, List<UploadedFile>>();
        source.forEach((key, values) -> copy.put(
                Objects.requireNonNull(key, "file field name"),
                List.copyOf(Objects.requireNonNull(values, "uploaded files"))
        ));
        return Map.copyOf(copy);
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
}
