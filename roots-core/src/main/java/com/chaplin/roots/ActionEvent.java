package com.chaplin.roots;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Immutable submitted values and files plus response effects for a live action. */
public final class ActionEvent {
    private static final int MAX_EFFECTS = 32;
    private static final int MAX_RESPONSE_COOKIES = 32;
    private final BrowserEvent browser;
    private final Map<String, List<String>> values;
    private final Map<String, List<UploadedFile>> files;
    private final Session session;
    private final RootsCache cache;
    private final Optional<AuthenticatedIdentity> identity;
    private final TraceContext traceContext;
    private final String mountPath;
    private final ClientConnection connection;
    private final Map<String, String> cookies;
    private String redirect;
    private final List<ClientEffect> effects = new ArrayList<>();
    private final List<ResponseCookie> responseCookies = new ArrayList<>();

    /** Creates a text-only action event with caching disabled.
     * @param type browser event wire name
     * @param values submitted values
     * @param session current session */
    public ActionEvent(String type, Map<String, List<String>> values, Session session) {
        this(BrowserEvent.empty(BrowserEvent.Type.fromWireName(type)), values, Map.of(), session,
                RootsCache.disabled());
    }

    /** Creates an action event with uploaded files and caching disabled.
     * @param type browser event wire name
     * @param values submitted values
     * @param files uploaded files
     * @param session current session */
    public ActionEvent(
            String type,
            Map<String, List<String>> values,
            Map<String, List<UploadedFile>> files,
            Session session
    ) {
        this(BrowserEvent.empty(BrowserEvent.Type.fromWireName(type)), values, files, session,
                RootsCache.disabled());
    }

    /** Creates an action event with application cache access.
     * @param type browser event wire name
     * @param values submitted values
     * @param files uploaded files
     * @param session current session
     * @param cache application cache */
    public ActionEvent(
            String type,
            Map<String, List<String>> values,
            Map<String, List<UploadedFile>> files,
            Session session,
            RootsCache cache
    ) {
        this(BrowserEvent.empty(BrowserEvent.Type.fromWireName(type)), values, files, session, cache,
                Optional.empty());
    }

    /** Creates an action event with validated browser details.
     * @param browser validated browser event
     * @param values submitted values
     * @param files uploaded files
     * @param session current session
     * @param cache application cache */
    public ActionEvent(
            BrowserEvent browser,
            Map<String, List<String>> values,
            Map<String, List<UploadedFile>> files,
            Session session,
            RootsCache cache
    ) {
        this(browser, values, files, session, cache, Optional.empty());
    }

    /** Creates an action event with validated browser and identity details.
     * @param browser validated browser event
     * @param values submitted values
     * @param files uploaded files
     * @param session current session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous */
    public ActionEvent(
            BrowserEvent browser,
            Map<String, List<String>> values,
            Map<String, List<UploadedFile>> files,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity
    ) {
        this(browser, values, files, session, cache, identity, TraceContext.create());
    }

    /** Creates an action event with validated browser, identity, and trace details.
     * @param browser validated browser event
     * @param values submitted values
     * @param files uploaded files
     * @param session current session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous
     * @param traceContext trace identifiers for this action request
     */
    public ActionEvent(
            BrowserEvent browser,
            Map<String, List<String>> values,
            Map<String, List<UploadedFile>> files,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity,
            TraceContext traceContext
    ) {
        this(browser, values, files, session, cache, identity, traceContext, "");
    }

    /** Creates an action event with deployment-path details.
     * @param browser validated browser event
     * @param values submitted values
     * @param files uploaded files
     * @param session current session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous
     * @param traceContext trace identifiers for this action request
     * @param mountPath external application mount, or empty at the origin root
     */
    public ActionEvent(
            BrowserEvent browser,
            Map<String, List<String>> values,
            Map<String, List<UploadedFile>> files,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity,
            TraceContext traceContext,
            String mountPath
    ) {
        this(browser, values, files, session, cache, identity, traceContext, mountPath,
                ClientConnection.unknown("http", "localhost"));
    }

    /** Creates an action event with deployment and client-connection details.
     * @param browser validated browser event
     * @param values submitted values
     * @param files uploaded files
     * @param session current session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous
     * @param traceContext trace identifiers for this action request
     * @param mountPath external application mount, or empty at the origin root
     * @param connection trusted client and public-origin details
     */
    public ActionEvent(
            BrowserEvent browser,
            Map<String, List<String>> values,
            Map<String, List<UploadedFile>> files,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity,
            TraceContext traceContext,
            String mountPath,
            ClientConnection connection
    ) {
        this(browser, values, files, session, cache, identity, traceContext, mountPath, connection, Map.of());
    }

    /** Creates an action event with deployment, connection, and request-cookie details.
     * @param browser validated browser event
     * @param values submitted values
     * @param files uploaded files
     * @param session current session
     * @param cache application cache
     * @param identity authenticated identity, or empty for anonymous
     * @param traceContext trace identifiers for this action request
     * @param mountPath external application mount, or empty at the origin root
     * @param connection trusted client and public-origin details
     * @param cookies validated request cookies
     */
    public ActionEvent(
            BrowserEvent browser,
            Map<String, List<String>> values,
            Map<String, List<UploadedFile>> files,
            Session session,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity,
            TraceContext traceContext,
            String mountPath,
            ClientConnection connection,
            Map<String, String> cookies
    ) {
        this.browser = Objects.requireNonNull(browser, "browser");
        var copiedValues = new LinkedHashMap<String, List<String>>();
        Objects.requireNonNull(values, "values").forEach((key, entries) -> copiedValues.put(
                Objects.requireNonNull(key, "value name"),
                List.copyOf(Objects.requireNonNull(entries, "values"))
        ));
        this.values = Map.copyOf(copiedValues);
        var copiedFiles = new LinkedHashMap<String, List<UploadedFile>>();
        Objects.requireNonNull(files, "files").forEach((key, entries) -> copiedFiles.put(
                Objects.requireNonNull(key, "file field name"),
                List.copyOf(Objects.requireNonNull(entries, "files"))
        ));
        this.files = Map.copyOf(copiedFiles);
        this.session = Objects.requireNonNull(session, "session");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.traceContext = Objects.requireNonNull(traceContext, "traceContext");
        this.mountPath = requireMountPath(mountPath);
        this.connection = Objects.requireNonNull(connection, "connection");
        this.cookies = CookieSyntax.copy(cookies);
    }

    /** Returns the browser event wire name.
     * @return event type */
    public String type() {
        return browser.type().wireName();
    }

    /** Returns validated keyboard, modifier, and pointer details.
     * @return browser event */
    public BrowserEvent browser() {
        return browser;
    }

    /** Finds the first submitted value.
     * @param name field name
     * @return first value, if present */
    public Optional<String> value(String name) {
        return values.getOrDefault(name, List.of()).stream().findFirst();
    }

    /** Requires a nonblank submitted value.
     * @param name field name
     * @return submitted value */
    public String required(String name) {
        return value(name)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing form value: " + name));
    }

    /** Returns all submitted values for a field.
     * @param name field name
     * @return immutable values */
    public List<String> values(String name) {
        return values.getOrDefault(name, List.of());
    }

    /** Returns all submitted text values.
     * @return immutable values by field */
    public Map<String, List<String>> allValues() {
        return values;
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
     * Record components may use annotations from {@code com.chaplin.roots.validation};
     * conversion and constraint failures are aggregated into a
     * {@link ValidationException} for the existing accessible browser error path.
     *
     * @param recordType target record type
     * @param <T> record type
     * @return constructed, validated form record
     */
    public <T> T bind(Class<T> recordType) {
        return com.chaplin.roots.validation.FormBinder.bind(values, files, recordType);
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

    /** Returns the authenticated identity executing this action.
     * @return authenticated identity, or empty for anonymous */
    public Optional<AuthenticatedIdentity> identity() {
        return identity;
    }

    /** Returns distributed trace identifiers for this action request.
     * @return action trace context
     */
    public TraceContext traceContext() {
        return traceContext;
    }

    /** Returns trusted client and public-origin details for this action request.
     * @return action connection details
     */
    public ClientConnection connection() {
        return connection;
    }

    /** Returns valid cookies supplied with this action request.
     * @return immutable cookies by name */
    public Map<String, String> cookies() {
        return cookies;
    }

    /** Finds a cookie supplied with this action request.
     * @param name cookie name
     * @return cookie value, if present */
    public Optional<String> cookie(String name) {
        return Optional.ofNullable(cookies.get(CookieSyntax.requireName(name)));
    }

    /** Returns the external path prefix at which the application is mounted.
     * @return empty string at the origin root, otherwise a leading-slash path
     */
    public String mountPath() {
        return mountPath;
    }

    /** Returns the application mount as a safe response-cookie path.
     * @return {@code /} at the origin root, otherwise the mount path */
    public String cookiePath() {
        return mountPath.isEmpty() ? "/" : mountPath;
    }

    /** Resolves an application-root-relative path beneath the current mount.
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

    /** Requests navigation after the action completes.
     * @param path absolute application path */
    public void redirect(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("Redirects must use an absolute application path");
        }
        redirect = path;
    }

    /** Returns the requested redirect.
     * @return redirect path, if set */
    public Optional<String> redirect() {
        return Optional.ofNullable(redirect);
    }

    /** Requests focus on a referenced element.
     * @param ref target element */
    public void focus(Ref ref) {
        addEffect(ClientEffect.focus(ref));
    }

    /** Requests removal of focus when the referenced element currently owns it.
     * Framework-directed blur does not invoke a bound server-side blur action.
     * @param ref target element */
    public void blur(Ref ref) {
        addEffect(ClientEffect.blur(ref));
    }

    /** Requests focus and full editable-text selection on a referenced element.
     * @param ref target input, textarea, or content-editable element */
    public void selectText(Ref ref) {
        addEffect(ClientEffect.selectText(ref));
    }

    /** Requests scrolling a referenced element into view.
     * @param ref target element */
    public void scrollIntoView(Ref ref) {
        addEffect(ClientEffect.scrollIntoView(ref));
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

    /** Requests copying text to the browser clipboard.
     * @param value text to copy */
    public void copyToClipboard(String value) {
        addEffect(ClientEffect.copyToClipboard(value));
    }

    /** Requests a non-urgent announcement through the framework's polite ARIA live region.
     * @param message plain announcement text */
    public void announce(String message) {
        addEffect(ClientEffect.announce(message));
    }

    /** Requests an urgent announcement through the framework's assertive ARIA live region.
     * Use sparingly because it may interrupt assistive technology output.
     * @param message plain announcement text */
    public void announceAssertively(String message) {
        addEffect(ClientEffect.announceAssertively(message));
    }

    /**
     * Requests that this action's authoritative DOM update use the browser View
     * Transition API. Browsers without the API, and users who prefer reduced
     * motion, receive the same update without animation.
     */
    public void viewTransition() {
        if (effects.stream().noneMatch(effect -> effect.type() == ClientEffect.Type.VIEW_TRANSITION)) {
            addEffect(ClientEffect.viewTransition());
        }
    }

    /** Returns requested browser effects in declaration order.
     * @return immutable effects */
    public List<ClientEffect> effects() {
        return List.copyOf(effects);
    }

    /** Appends a validated cookie to the successful action response.
     * Cookies are discarded if the action or authoritative rerender fails.
     * @param cookie response cookie */
    public void setCookie(ResponseCookie cookie) {
        if (responseCookies.size() >= MAX_RESPONSE_COOKIES) {
            throw new IllegalStateException("An action may set at most 32 response cookies");
        }
        responseCookies.add(Objects.requireNonNull(cookie, "cookie"));
    }

    /** Expires a host-only cookie at this application's mount path.
     * Use {@link #setCookie(ResponseCookie)} when domain or partition attributes must match.
     * @param name cookie name */
    public void deleteCookie(String name) {
        setCookie(ResponseCookie.expire(name)
                .path(cookiePath())
                .secure(connection.scheme().equals("https"))
                .build());
    }

    /** Returns cookies queued for the successful action response.
     * @return immutable response cookies */
    public List<ResponseCookie> responseCookies() {
        return List.copyOf(responseCookies);
    }

    private void addEffect(ClientEffect effect) {
        if (effects.size() >= MAX_EFFECTS) {
            throw new IllegalStateException("An action may request at most 32 browser effects");
        }
        effects.add(Objects.requireNonNull(effect, "effect"));
    }
}
