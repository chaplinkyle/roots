package com.chaplin.roots.html;

import com.chaplin.roots.Action;
import com.chaplin.roots.Actions;
import com.chaplin.roots.BrowserEvent;
import com.chaplin.roots.Ref;
import com.chaplin.roots.OptimisticEffect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A fluent HTML element with attributes, children, and server-action bindings. */
public final class Element implements Node {
    static final Set<String> VOID_TAGS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"
    );

    private final String tag;
    private Map<String, String> attributes;
    private List<Node> children;
    private Map<String, EventBinding> events;
    private List<OptimisticEffect> optimisticEffects;

    Element(String tag, Object... children) {
        if (!HtmlNames.tag(tag)) {
            throw new IllegalArgumentException("Invalid HTML tag: " + tag);
        }
        this.tag = tag;
        child(children);
    }

    /**
     * Adds, replaces, or removes an HTML attribute.
     * @param name attribute name
     * @param value attribute value; {@code null} or {@code false} removes it
     * @return this element
     */
    public Element attr(String name, Object value) {
        Objects.requireNonNull(name, "name");
        if (!HtmlNames.attribute(name)) {
            throw new IllegalArgumentException("Invalid HTML attribute: " + name);
        }
        var normalizedName = name.toLowerCase(Locale.ROOT);
        if (normalizedName.equals("data-roots-component")) {
            throw new IllegalArgumentException("data-roots-component is reserved for the Roots renderer");
        }
        if (normalizedName.startsWith("on")) {
            throw new IllegalArgumentException("Inline browser event handlers are not allowed; use onClick/onSubmit/onChange");
        }
        // HTML datasets are case-insensitive; use their browser spelling for
        // renderer ownership checks, including attributes supplied through attr().
        var attributeName = normalizedName.startsWith("data-") || normalizedName.equals("contenteditable")
                ? normalizedName : name;
        if (value == null || Boolean.FALSE.equals(value)) {
            if (attributes != null) attributes.remove(attributeName);
        } else {
            if (attributes == null) attributes = new LinkedHashMap<>(4);
            attributes.put(attributeName, Boolean.TRUE.equals(value) ? "" : String.valueOf(value));
        }
        return this;
    }

    /** Sets the element identifier.
     * @param id element identifier
     * @return this element */
    public Element id(String id) {
        return attr("id", id);
    }

    /** Sets CSS classes.
     * @param className CSS class value
     * @return this element */
    public Element className(String className) {
        return attr("class", className);
    }

    /** Sets the form-control name.
     * @param name form-control name
     * @return this element */
    public Element name(String name) {
        return attr("name", name);
    }

    /** Sets the form-control value.
     * @param value form-control value
     * @return this element */
    public Element value(Object value) {
        return attr("value", value);
    }

    /** Sets the element type.
     * @param type element or control type
     * @return this element */
    public Element type(String type) {
        return attr("type", type);
    }

    /** Sets the link destination.
     * @param href link destination
     * @return this element */
    public Element href(String href) {
        return attr("href", href);
    }

    /** Sets a form-control hint.
     * @param placeholder form-control hint
     * @return this element */
    public Element placeholder(String placeholder) {
        return attr("placeholder", placeholder);
    }

    /** Sets an ARIA attribute.
     * @param name ARIA attribute suffix
     * @param value attribute value
     * @return this element */
    public Element aria(String name, Object value) {
        return attr("aria-" + name, value instanceof Boolean ? value.toString() : value);
    }

    /** Sets a data attribute.
     * @param name data attribute suffix
     * @param value attribute value
     * @return this element */
    public Element data(String name, Object value) {
        return attr("data-" + name, value);
    }

    /**
     * Gives the reconciler stable identity when siblings are inserted, removed, or reordered.
     * @param key stable sibling key
     * @return this element
     */
    public Element key(Object key) {
        return attr("data-roots-key", key);
    }

    /**
     * Declares a browser-owned widget inside this keyed container. The same-origin
     * ES module exports {@code mount(host, context)} and may return update/destroy
     * callbacks. Supply string properties with {@code data("widget-name", value)}.
     * Children are static, non-interactive fallback content; keep live components
     * and server-bound controls outside this boundary.
     * @param key stable sibling identity, non-blank and at most 256 characters
     * @param moduleUrl application-root-relative module URL; optional version query
     * @return this element
     */
    public Element widget(String key, String moduleUrl) {
        if (key == null || key.isBlank() || key.length() > 256) {
            throw new IllegalArgumentException("Widget key must contain 1 to 256 characters");
        }
        validateWidgetModule(moduleUrl);
        return key(key).attr("data-roots-widget", moduleUrl);
    }

    static void validateWidgetModule(String moduleUrl) {
        Objects.requireNonNull(moduleUrl, "moduleUrl");
        var uri = java.net.URI.create(moduleUrl);
        if (moduleUrl.length() > 2048 || !moduleUrl.startsWith("/") || moduleUrl.startsWith("//")
                || uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null
                || moduleUrl.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Widget modules must use an application-root-relative URL without a fragment");
        }
    }

    /** Assigns a typed browser-effect target.
     * @param ref target reference
     * @return this element */
    public Element ref(Ref ref) {
        return attr("data-roots-ref", Objects.requireNonNull(ref).id());
    }

    /**
     * Marks this element as the nearest pending boundary for descendant server
     * actions. The browser temporarily adds {@code data-roots-pending} and
     * {@code aria-busy="true"} while an action in this scope is running.
     *
     * @return this element
     */
    public Element pendingScope() {
        return attr("data-roots-pending-scope", true);
    }

    /**
     * Coalesces input events until the user stops editing for the given interval.
     * Only {@code input} actions are delayed. Other actions flush pending input
     * first, so a submit observes the final edit. Zero disables the delay.
     *
     * @param delay quiet interval from zero through one minute
     * @return this element
     * @throws IllegalArgumentException if the interval is negative, longer than
     * one minute, or positive but shorter than one millisecond
     */
    public Element debounceInput(java.time.Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative() || delay.compareTo(java.time.Duration.ofMinutes(1)) > 0
                || (!delay.isZero() && delay.toMillis() == 0)) {
            throw new IllegalArgumentException("Input debounce must be zero or between 1 and 60000 milliseconds");
        }
        return attr("data-roots-input-debounce", delay.isZero() ? null : delay.toMillis());
    }

    /**
     * Bounds how long the browser waits for this element's action response.
     * The default is 30 seconds. A timeout cannot cancel or roll back a server
     * mutation: Roots pauses further actions in that view and reports an unknown
     * outcome. Use background work for operations exceeding five minutes.
     *
     * @param timeout duration from 100 milliseconds through five minutes
     * @return this element
     */
    public Element actionTimeout(java.time.Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(java.time.Duration.ofMillis(100)) < 0
                || timeout.compareTo(java.time.Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Action timeout must be between 100 and 300000 milliseconds");
        }
        return attr("data-roots-action-timeout", timeout.toMillis());
    }

    /**
     * Requests progressive View Transition API animation for this Roots client
     * navigation. The element must be an anchor; unsupported browsers and users
     * who prefer reduced motion use the normal navigation commit.
     *
     * @return this anchor
     */
    public Element viewTransition() {
        if (!tag.equals("a")) {
            throw new IllegalStateException("View-transition navigation requires an anchor element");
        }
        return attr("data-roots-view-transition", true);
    }

    /**
     * Applies reversible browser mutations until this element's server action
     * succeeds or fails. Effects are bounded and contain no application script.
     *
     * @param effects typed mutations to apply in declaration order
     * @return this element
     */
    public Element optimistic(OptimisticEffect... effects) {
        Objects.requireNonNull(effects, "effects");
        if (optimisticEffects().size() + effects.length > 32) {
            throw new IllegalArgumentException("An element may declare at most 32 optimistic effects");
        }
        if (optimisticEffects == null) optimisticEffects = new ArrayList<>(effects.length);
        for (var effect : effects) {
            optimisticEffects.add(Objects.requireNonNull(effect, "optimistic effect"));
        }
        return this;
    }

    /**
     * Appends child nodes, iterables, or values converted to escaped text.
     * @param values values to append
     * @return this element
     */
    public Element child(Object... values) {
        if (values.length > 0 && children == null) children = new ArrayList<>(values.length);
        for (var value : values) {
            Html.addValue(children, value);
        }
        return this;
    }

    /** Binds click activation to an action.
     * @param actionName wire action name
     * @param action handler
     * @return this element */
    public Element onClick(String actionName, Action action) {
        return on("click", actionName, action);
    }

    /** Binds click activation to an annotated action.
     * @param target annotated target
     * @param annotatedAction action name
     * @return this element */
    public Element onClick(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onClick(action.name(), action.action());
    }

    /** Binds form submission to an action.
     * @param actionName wire action name
     * @param action handler
     * @return this element */
    public Element onSubmit(String actionName, Action action) {
        return on("submit", actionName, action);
    }

    /** Binds form submission to an annotated action.
     * @param target annotated target
     * @param annotatedAction action name
     * @return this element */
    public Element onSubmit(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onSubmit(action.name(), action.action());
    }

    /** Binds a form-control change to an action.
     * @param actionName wire action name
     * @param action handler
     * @return this element */
    public Element onChange(String actionName, Action action) {
        return on("change", actionName, action);
    }

    /** Binds a form-control change to an annotated action.
     * @param target annotated target
     * @param annotatedAction action name
     * @return this element */
    public Element onChange(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onChange(action.name(), action.action());
    }

    /**
     * Binds immediate form-control input to an action.
     *
     * @param actionName wire action name
     * @param action handler
     * @return this element
     */
    public Element onInput(String actionName, Action action) {
        return on("input", actionName, action);
    }

    /**
     * Binds immediate form-control input to an annotated action.
     *
     * @param target annotated target
     * @param annotatedAction action name
     * @return this element
     */
    public Element onInput(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onInput(action.name(), action.action());
    }

    /**
     * Binds keyboard key-down to an action.
     *
     * @param actionName wire action name
     * @param action handler
     * @return this element
     */
    public Element onKeyDown(String actionName, Action action) {
        return on("keydown", actionName, action);
    }

    /**
     * Binds keyboard key-down to an annotated action.
     *
     * @param target annotated target
     * @param annotatedAction action name
     * @return this element
     */
    public Element onKeyDown(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onKeyDown(action.name(), action.action());
    }

    /**
     * Binds keyboard key-up to an action.
     *
     * @param actionName wire action name
     * @param action handler
     * @return this element
     */
    public Element onKeyUp(String actionName, Action action) {
        return on("keyup", actionName, action);
    }

    /**
     * Binds keyboard key-up to an annotated action.
     *
     * @param target annotated target
     * @param annotatedAction action name
     * @return this element
     */
    public Element onKeyUp(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onKeyUp(action.name(), action.action());
    }

    /**
     * Binds captured focus to an action.
     *
     * @param actionName wire action name
     * @param action handler
     * @return this element
     */
    public Element onFocus(String actionName, Action action) {
        return on("focus", actionName, action);
    }

    /**
     * Binds captured focus to an annotated action.
     *
     * @param target annotated target
     * @param annotatedAction action name
     * @return this element
     */
    public Element onFocus(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onFocus(action.name(), action.action());
    }

    /**
     * Binds captured blur to an action.
     *
     * @param actionName wire action name
     * @param action handler
     * @return this element
     */
    public Element onBlur(String actionName, Action action) {
        return on("blur", actionName, action);
    }

    /**
     * Binds captured blur to an annotated action.
     *
     * @param target annotated target
     * @param annotatedAction action name
     * @return this element
     */
    public Element onBlur(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onBlur(action.name(), action.action());
    }

    /**
     * Binds double-click to an action.
     *
     * @param actionName wire action name
     * @param action handler
     * @return this element
     */
    public Element onDoubleClick(String actionName, Action action) {
        return on("dblclick", actionName, action);
    }

    /**
     * Binds double-click to an annotated action.
     *
     * @param target annotated target
     * @param annotatedAction action name
     * @return this element
     */
    public Element onDoubleClick(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onDoubleClick(action.name(), action.action());
    }

    /**
     * Binds pointer-down to an action.
     *
     * @param actionName wire action name
     * @param action handler
     * @return this element
     */
    public Element onPointerDown(String actionName, Action action) {
        return on("pointerdown", actionName, action);
    }

    /**
     * Binds pointer-down to an annotated action.
     *
     * @param target annotated target
     * @param annotatedAction action name
     * @return this element
     */
    public Element onPointerDown(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onPointerDown(action.name(), action.action());
    }

    /**
     * Binds pointer-up to an action.
     *
     * @param actionName wire action name
     * @param action handler
     * @return this element
     */
    public Element onPointerUp(String actionName, Action action) {
        return on("pointerup", actionName, action);
    }

    /**
     * Binds pointer-up to an annotated action.
     *
     * @param target annotated target
     * @param annotatedAction action name
     * @return this element
     */
    public Element onPointerUp(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onPointerUp(action.name(), action.action());
    }

    /**
     * Binds a supported browser event to a server action.
     * @param browserEvent supported wire event name
     * @param actionName wire action name
     * @param action handler
     * @return this element
     */
    public Element on(String browserEvent, String actionName, Action action) {
        var eventType = BrowserEvent.Type.fromWireName(browserEvent);
        if (!HtmlNames.action(actionName)) {
            throw new IllegalArgumentException("Invalid action name: " + actionName);
        }
        if (events == null) events = new LinkedHashMap<>(4);
        events.put(eventType.wireName(), new EventBinding(actionName, Objects.requireNonNull(action)));
        return this;
    }

    String tag() {
        return tag;
    }

    Map<String, String> attributes() {
        return attributes == null ? Map.of() : attributes;
    }

    List<Node> children() {
        return children == null ? List.of() : children;
    }

    Map<String, EventBinding> events() {
        return events == null ? Map.of() : events;
    }

    List<OptimisticEffect> optimisticEffects() {
        return optimisticEffects == null ? List.of() : optimisticEffects;
    }

    record EventBinding(String name, Action action) {
    }
}
