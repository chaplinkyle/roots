package dev.roots.html;

import dev.roots.Action;
import dev.roots.Actions;
import dev.roots.Ref;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class Element implements Node {
    static final Set<String> VOID_TAGS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"
    );

    private final String tag;
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private final List<Node> children = new ArrayList<>();
    private final Map<String, EventBinding> events = new LinkedHashMap<>();

    Element(String tag, Object... children) {
        if (!tag.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("Invalid HTML tag: " + tag);
        }
        this.tag = tag;
        child(children);
    }

    public Element attr(String name, Object value) {
        Objects.requireNonNull(name, "name");
        if (!name.matches("[A-Za-z_:][A-Za-z0-9_.:-]*")) {
            throw new IllegalArgumentException("Invalid HTML attribute: " + name);
        }
        if (name.toLowerCase(Locale.ROOT).startsWith("on")) {
            throw new IllegalArgumentException("Inline browser event handlers are not allowed; use onClick/onSubmit/onChange");
        }
        if (value == null || Boolean.FALSE.equals(value)) {
            attributes.remove(name);
        } else {
            attributes.put(name, Boolean.TRUE.equals(value) ? "" : String.valueOf(value));
        }
        return this;
    }

    public Element id(String id) {
        return attr("id", id);
    }

    public Element className(String className) {
        return attr("class", className);
    }

    public Element name(String name) {
        return attr("name", name);
    }

    public Element value(Object value) {
        return attr("value", value);
    }

    public Element type(String type) {
        return attr("type", type);
    }

    public Element href(String href) {
        return attr("href", href);
    }

    public Element placeholder(String placeholder) {
        return attr("placeholder", placeholder);
    }

    public Element aria(String name, Object value) {
        return attr("aria-" + name, value);
    }

    public Element data(String name, Object value) {
        return attr("data-" + name, value);
    }

    /** Gives the reconciler stable identity when siblings are inserted, removed, or reordered. */
    public Element key(Object key) {
        return attr("data-roots-key", key);
    }

    public Element ref(Ref ref) {
        return attr("data-roots-ref", Objects.requireNonNull(ref).id());
    }

    public Element child(Object... values) {
        for (var value : values) {
            Html.addValue(children, value);
        }
        return this;
    }

    public Element onClick(String actionName, Action action) {
        return on("click", actionName, action);
    }

    public Element onClick(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onClick(action.name(), action.action());
    }

    public Element onSubmit(String actionName, Action action) {
        return on("submit", actionName, action);
    }

    public Element onSubmit(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onSubmit(action.name(), action.action());
    }

    public Element onChange(String actionName, Action action) {
        return on("change", actionName, action);
    }

    public Element onChange(Object target, String annotatedAction) {
        var action = Actions.bind(target, annotatedAction);
        return onChange(action.name(), action.action());
    }

    public Element on(String browserEvent, String actionName, Action action) {
        if (!browserEvent.matches("[a-z]+")) {
            throw new IllegalArgumentException("Invalid browser event: " + browserEvent);
        }
        if (!actionName.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
            throw new IllegalArgumentException("Invalid action name: " + actionName);
        }
        events.put(browserEvent, new EventBinding(actionName, Objects.requireNonNull(action)));
        return this;
    }

    String tag() {
        return tag;
    }

    Map<String, String> attributes() {
        return attributes;
    }

    List<Node> children() {
        return children;
    }

    Map<String, EventBinding> events() {
        return events;
    }

    record EventBinding(String name, Action action) {
    }
}
