package com.chaplin.roots.internal;

import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.Actions;
import com.chaplin.roots.AuthenticatedIdentity;
import com.chaplin.roots.BrowserEvent;
import com.chaplin.roots.ClientEffect;
import com.chaplin.roots.ClientConnection;
import com.chaplin.roots.Layout;
import com.chaplin.roots.InstanceFactory;
import com.chaplin.roots.Page;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.Response;
import com.chaplin.roots.ResponseCookie;
import com.chaplin.roots.RootsCache;
import com.chaplin.roots.Session;
import com.chaplin.roots.UploadedFile;
import com.chaplin.roots.ValidationException;
import com.chaplin.roots.TraceContext;
import com.chaplin.roots.annotation.ViewComponent;
import com.chaplin.roots.html.HtmlRenderer;
import com.chaplin.roots.html.Node;
import com.chaplin.roots.html.RenderedTree;

import java.util.ArrayList;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class LiveView {
    private static final System.Logger LOG = System.getLogger(LiveView.class.getName());
    private static final PatchEvent CLOSED = new PatchEvent(null, true);

    private final String id = SessionStore.token();
    private final String csrf = SessionStore.token();
    private final String sessionId;
    private final Page page;
    private final List<Layout> layouts;
    private final PageContext context;
    private final InstanceFactory instanceFactory;
    private final Map<String, String> parameters;
    private final Map<String, List<String>> query;
    private final List<String> authorizationPolicies;
    private final Set<String> knownAuthorizationPolicies;
    private final Optional<AuthenticatedIdentity> identity;
    private final Set<com.chaplin.roots.Component> mountedComponents = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayBlockingQueue<PatchEvent> pushedPatches = new ArrayBlockingQueue<>(8);
    private final AtomicReference<StreamAttachment> streamAttachment = new AtomicReference<>();
    private boolean closed;
    private boolean actionOutcomeUncertain;
    private boolean pageMounted;
    private int mountedLayoutCount;
    private volatile long lastAccess = System.currentTimeMillis();
    private RenderedTree rendered;
    private ResolvedMetadata metadata;
    private long revision;
    private long patchBaseRevision;
    private String patchHtml;
    private String patchScope;

    LiveView(
            ConventionRouter.PageMatch match,
            String path,
            Map<String, List<String>> query,
            Session session,
            InstanceFactory instanceFactory,
            Set<String> knownAuthorizationPolicies,
            RootsCache cache,
            Optional<AuthenticatedIdentity> identity,
            TraceContext traceContext,
            String mountPath,
            ClientConnection connection,
            Map<String, String> cookies
    ) {
        sessionId = session.id();
        this.instanceFactory = instanceFactory;
        parameters = match.parameters();
        this.query = query;
        authorizationPolicies = match.route().authorizationPolicies();
        this.knownAuthorizationPolicies = Set.copyOf(knownAuthorizationPolicies);
        this.identity = Objects.requireNonNull(identity, "identity");
        page = create(match.route().type());
        var createdLayouts = new ArrayList<Layout>();
        try {
            for (var type : match.route().layouts()) {
                createdLayouts.add(create(type));
            }
        } catch (RuntimeException | Error failure) {
            for (var index = createdLayouts.size() - 1; index >= 0; index--) {
                destroy("partially constructed layout " + createdLayouts.get(index).getClass().getName(),
                        createdLayouts.get(index));
            }
            destroy("partially constructed page " + page.getClass().getName(), page);
            throw failure;
        }
        layouts = List.copyOf(createdLayouts);
        context = new PageContext(
                path, match.parameters(), query, session, cache, identity, traceContext, mountPath, connection
        );
        context.updateCookies(cookies);
        context.attachUpdater(this::update);
        try {
            synchronized (this) {
                render();
                pageMounted = true;
                page.onMount(context);
                for (var layout : layouts) {
                    mountedLayoutCount++;
                    layout.onMount(context);
                }
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    synchronized ActionResult invoke(
            String actionName,
            BrowserEvent browserEvent,
            Map<String, List<String>> values,
            Map<String, List<UploadedFile>> files,
            Session session,
            Optional<AuthenticatedIdentity> currentIdentity,
            TraceContext traceContext,
            ClientConnection connection,
            Map<String, String> cookies,
            AuthorizationGate authorization
    ) throws Exception {
        try {
            touch();
            if (closed || !sessionId.equals(session.id()) || !identity.equals(currentIdentity)) {
                close();
                throw new StaleViewException();
            }
            if (actionOutcomeUncertain) throw new UncertainActionException(null);
            var action = rendered.actions().get(actionName);
            if (action == null) {
                throw new StaleViewException();
            }
            var policies = new LinkedHashSet<>(authorizationPolicies);
            policies.addAll(action.authorizationPolicies());
            for (var policy : policies) {
                var rejection = Objects.requireNonNull(
                        authorization.authorize(policy),
                        "Authorization policy result"
                );
                if (rejection.isPresent()) {
                    throw new AuthorizationRejected(rejection.orElseThrow());
                }
            }
            context.updateTraceContext(traceContext);
            context.updateConnection(connection);
            context.updateCookies(cookies);
            var event = new ActionEvent(
                    browserEvent,
                    values,
                    files,
                    session,
                    context.cache(),
                    identity,
                    traceContext,
                    context.mountPath(),
                    connection,
                    cookies
            );
            boolean handlerReturned = false;
            try {
                action.handle(event);
                handlerReturned = true;
                if (event.redirect().isPresent()) {
                    return new ActionResult(null, event.redirect().orElseThrow(), event.effects(), event.responseCookies());
                }
                render();
                return new ActionResult(snapshot(), null, event.effects(), event.responseCookies());
            } catch (Exception | Error failure) {
                // Validation is an explicit application contract: reject before
                // changing business state. A render failure is never a rejection.
                if (!handlerReturned && (failure instanceof ValidationException
                        || failure instanceof IllegalArgumentException)) throw failure;
                actionOutcomeUncertain = true;
                throw new UncertainActionException(failure);
            }
        } finally {
            touch();
        }
    }

    synchronized Snapshot snapshot() {
        touch();
        return new Snapshot(rendered.html(), metadata, revision, patchBaseRevision, patchHtml, patchScope);
    }

    synchronized Snapshot reconnectSnapshot() {
        touch();
        return fullPatchSnapshot();
    }

    synchronized Inspection inspect() {
        touch();
        var componentCounts = new IdentityHashMap<com.chaplin.roots.Component, Integer>();
        var componentOrder = new ArrayList<com.chaplin.roots.Component>();
        for (var component : rendered.components()) {
            if (!componentCounts.containsKey(component)) {
                componentOrder.add(component);
            }
            componentCounts.merge(component, 1, Integer::sum);
        }
        var components = componentOrder.stream().map(component -> {
            var type = component.getClass();
            var annotation = type.getAnnotation(ViewComponent.class);
            var name = annotation == null || annotation.value().isBlank()
                    ? type.getSimpleName()
                    : annotation.value();
            return new ComponentInspection(
                    name,
                    type.getName(),
                    Integer.toHexString(System.identityHashCode(component)),
                    componentCounts.get(component)
            );
        }).toList();
        var actions = new LinkedHashMap<String, Actions.ActionDescription>();
        rendered.actions().forEach((name, action) -> actions.put(name, Actions.describe(action)));
        return new Inspection(
                context.path(),
                revision,
                page.getClass().getName(),
                layouts.stream().map(layout -> layout.getClass().getName()).toList(),
                components,
                Collections.unmodifiableMap(actions)
        );
    }

    String id() {
        return id;
    }

    String csrf() {
        return csrf;
    }

    String sessionId() {
        return sessionId;
    }

    String mountPath() {
        return context.mountPath();
    }

    String path() {
        return context.path();
    }

    Map<String, String> parameters() {
        return parameters;
    }

    Map<String, List<String>> query() {
        return query;
    }

    List<String> authorizationPolicies() {
        return authorizationPolicies;
    }

    Optional<AuthenticatedIdentity> identity() {
        return identity;
    }

    long lastAccess() {
        return lastAccess;
    }

    synchronized boolean expireIfIdle(long cutoff) {
        if (lastAccess >= cutoff || closed) {
            return closed;
        }
        close();
        return true;
    }

    synchronized boolean closed() {
        return closed;
    }

    PatchEvent nextPatch(Duration timeout) throws InterruptedException {
        touch();
        return pushedPatches.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    StreamAttachment attachStream() {
        var next = new StreamAttachment(Thread.currentThread());
        var previous = streamAttachment.getAndSet(next);
        if (previous != null) {
            previous.thread().interrupt();
        }
        return next;
    }

    boolean streamAttached(StreamAttachment attachment) {
        return streamAttachment.get() == attachment;
    }

    void detachStream(StreamAttachment attachment) {
        streamAttachment.compareAndSet(attachment, null);
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        pushedPatches.clear();
        pushedPatches.offer(CLOSED);
        for (var component : List.copyOf(mountedComponents)) {
            unmount("component " + component.getClass().getName(), () -> component.onUnmount(context));
        }
        mountedComponents.clear();
        for (var index = mountedLayoutCount - 1; index >= 0; index--) {
            var layout = layouts.get(index);
            unmount("layout " + layout.getClass().getName(), () -> layout.onUnmount(context));
        }
        mountedLayoutCount = 0;
        if (pageMounted) {
            unmount("page " + page.getClass().getName(), () -> page.onUnmount(context));
            pageMounted = false;
        }
        for (var index = layouts.size() - 1; index >= 0; index--) {
            var layout = layouts.get(index);
            destroy("layout " + layout.getClass().getName(), layout);
        }
        destroy("page " + page.getClass().getName(), page);
    }

    private void render() {
        metadata = metadata();
        Node tree = page.render(context);
        for (var index = layouts.size() - 1; index >= 0; index--) {
            tree = layouts.get(index).render(context, tree);
        }
        var nextRendered = HtmlRenderer.render(tree, context);
        nextRendered.actions().forEach((name, action) -> action.authorizationPolicies().forEach(policy -> {
            if (!knownAuthorizationPolicies.contains(policy)) {
                throw new IllegalStateException("No authorization policy named '" + policy
                        + "' is registered for action " + name);
            }
        }));
        var patch = RenderedTreePatch.between(rendered, nextRendered);
        patchBaseRevision = revision;
        patchHtml = patch.html();
        patchScope = patch.scope();
        rendered = nextRendered;
        revision++;
        reconcileComponentLifecycle(rendered.components());
    }

    private synchronized void update(Runnable mutation) {
        if (closed) {
            return;
        }
        mutation.run();
        render();
        var next = new PatchEvent(fullPatchSnapshot(), false);
        if (!pushedPatches.offer(next)) {
            pushedPatches.poll();
            pushedPatches.offer(next);
        }
    }

    private void reconcileComponentLifecycle(List<com.chaplin.roots.Component> renderedComponents) {
        var next = Collections.newSetFromMap(new IdentityHashMap<com.chaplin.roots.Component, Boolean>());
        next.addAll(renderedComponents);
        var removed = new ArrayList<com.chaplin.roots.Component>();
        for (var component : mountedComponents) {
            if (!next.contains(component)) {
                removed.add(component);
            }
        }
        var added = new ArrayList<com.chaplin.roots.Component>();
        for (var component : next) {
            if (!mountedComponents.contains(component)) {
                added.add(component);
            }
        }
        for (var component : removed) {
            mountedComponents.remove(component);
            unmount("component " + component.getClass().getName(), () -> component.onUnmount(context));
        }
        for (var component : added) {
            mountedComponents.add(component);
            try {
                component.onMount(context);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }
    }

    private ResolvedMetadata metadata() {
        return MetadataSupport.resolve(page, layouts, context);
    }

    private void touch() {
        lastAccess = System.currentTimeMillis();
    }

    private Snapshot fullPatchSnapshot() {
        return new Snapshot(rendered.html(), metadata, revision, patchBaseRevision, rendered.html(), null);
    }

    private static void unmount(String description, Runnable callback) {
        try {
            callback.run();
        } catch (Throwable failure) {
            LOG.log(System.Logger.Level.WARNING, "Failure while unmounting " + description, failure);
        }
    }

    private void destroy(String description, Object instance) {
        try {
            instanceFactory.destroy(instance);
        } catch (Throwable failure) {
            LOG.log(System.Logger.Level.WARNING, "Failure while destroying " + description, failure);
        }
    }

    private <T> T create(Class<? extends T> type) {
        try {
            return instanceFactory.instantiate(type);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create " + type.getName(), exception);
        }
    }

    record Snapshot(
            String html,
            ResolvedMetadata metadata,
            long revision,
            long baseRevision,
            String patchHtml,
            String patchScope
    ) {
    }

    record Inspection(
            String path,
            long revision,
            String pageType,
            List<String> layoutTypes,
            List<ComponentInspection> components,
            Map<String, Actions.ActionDescription> actions
    ) {
    }

    record ComponentInspection(String name, String type, String identity, int occurrences) {
    }

    record ActionResult(
            Snapshot snapshot,
            String redirect,
            List<ClientEffect> effects,
            List<ResponseCookie> responseCookies
    ) {
    }

    record PatchEvent(Snapshot snapshot, boolean closed) {
    }

    record StreamAttachment(Thread thread) {
    }

    @FunctionalInterface
    interface AuthorizationGate {
        Optional<Response> authorize(String policy) throws Exception;
    }

    static final class AuthorizationRejected extends Exception {
        private final Response response;

        AuthorizationRejected(Response response) {
            this.response = response;
        }

        Response response() {
            return response;
        }
    }

    static final class StaleViewException extends Exception {
    }

    static final class UncertainActionException extends Exception {
        UncertainActionException(Throwable cause) { super(cause); }
    }
}
