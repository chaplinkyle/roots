package dev.roots.internal;

import dev.roots.ActionEvent;
import dev.roots.ClientEffect;
import dev.roots.Layout;
import dev.roots.Metadata;
import dev.roots.Page;
import dev.roots.PageContext;
import dev.roots.Session;
import dev.roots.annotation.PageMetadata;
import dev.roots.html.HtmlRenderer;
import dev.roots.html.Node;
import dev.roots.html.RenderedTree;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class LiveView {
    private final String id = SessionStore.token();
    private final String csrf = SessionStore.token();
    private final String sessionId;
    private final Page page;
    private final List<Layout> layouts;
    private final PageContext context;
    private final Set<dev.roots.Component> mountedComponents = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayBlockingQueue<Snapshot> pushedPatches = new ArrayBlockingQueue<>(8);
    private boolean closed;
    private volatile long lastAccess = System.currentTimeMillis();
    private RenderedTree rendered;
    private Metadata metadata;
    private long revision;

    LiveView(ConventionRouter.PageMatch match, String path, Map<String, List<String>> query, Session session) {
        sessionId = session.id();
        page = instantiate(match.route().type());
        layouts = match.route().layouts().stream().map(LiveView::<Layout>instantiate).toList();
        context = new PageContext(path, match.parameters(), query, session);
        context.attachUpdater(this::update);
        synchronized (this) {
            render();
            page.onMount(context);
            layouts.forEach(layout -> layout.onMount(context));
        }
    }

    synchronized ActionResult invoke(
            String actionName,
            String eventType,
            Map<String, List<String>> values,
            Session session
    ) throws Exception {
        touch();
        if (!sessionId.equals(session.id())) {
            throw new StaleViewException();
        }
        var action = rendered.actions().get(actionName);
        if (action == null) {
            throw new StaleViewException();
        }
        var event = new ActionEvent(eventType, values, session);
        action.handle(event);
        if (event.redirect().isPresent()) {
            return new ActionResult(null, event.redirect().orElseThrow(), event.effects());
        }
        render();
        return new ActionResult(snapshot(), null, event.effects());
    }

    synchronized Snapshot snapshot() {
        touch();
        return new Snapshot(rendered.html(), metadata, revision);
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

    long lastAccess() {
        return lastAccess;
    }

    Snapshot nextPatch(Duration timeout) throws InterruptedException {
        touch();
        return pushedPatches.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        mountedComponents.forEach(component -> component.onUnmount(context));
        mountedComponents.clear();
        for (var index = layouts.size() - 1; index >= 0; index--) {
            layouts.get(index).onUnmount(context);
        }
        page.onUnmount(context);
    }

    private void render() {
        metadata = metadata();
        Node tree = page.render(context);
        for (var index = layouts.size() - 1; index >= 0; index--) {
            tree = layouts.get(index).render(context, tree);
        }
        rendered = HtmlRenderer.render(tree, context);
        revision++;
        reconcileComponentLifecycle(rendered.components());
    }

    private synchronized void update(Runnable mutation) {
        if (closed) {
            return;
        }
        mutation.run();
        render();
        var next = snapshot();
        if (!pushedPatches.offer(next)) {
            pushedPatches.poll();
            pushedPatches.offer(next);
        }
    }

    private void reconcileComponentLifecycle(List<dev.roots.Component> renderedComponents) {
        var next = Collections.newSetFromMap(new IdentityHashMap<dev.roots.Component, Boolean>());
        next.addAll(renderedComponents);
        var removed = new ArrayList<dev.roots.Component>();
        for (var component : mountedComponents) {
            if (!next.contains(component)) {
                removed.add(component);
            }
        }
        var added = new ArrayList<dev.roots.Component>();
        for (var component : next) {
            if (!mountedComponents.contains(component)) {
                added.add(component);
            }
        }
        mountedComponents.clear();
        mountedComponents.addAll(next);
        removed.forEach(component -> component.onUnmount(context));
        added.forEach(component -> component.onMount(context));
    }

    private Metadata metadata() {
        var annotation = page.getClass().getAnnotation(PageMetadata.class);
        if (annotation != null) {
            return Metadata.of(annotation.title(), annotation.description(), annotation.stylesheets());
        }
        return page.metadata(context);
    }

    private void touch() {
        lastAccess = System.currentTimeMillis();
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

    record Snapshot(String html, Metadata metadata, long revision) {
    }

    record ActionResult(Snapshot snapshot, String redirect, List<ClientEffect> effects) {
    }

    static final class StaleViewException extends Exception {
    }
}
