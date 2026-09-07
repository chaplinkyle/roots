package com.chaplin.roots.html;

import com.chaplin.roots.Action;
import com.chaplin.roots.Actions;
import com.chaplin.roots.Component;
import com.chaplin.roots.ErrorBoundary;
import com.chaplin.roots.PageContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/** Renders Roots node trees to escaped HTML and associated live bindings. */
public final class HtmlRenderer {
    private static final Set<String> URL_ATTRIBUTES = Set.of("action", "formaction", "href", "poster", "src", "data-roots-widget");
    private final StringBuilder output = new StringBuilder();
    private final Map<String, Action> actions = new LinkedHashMap<>();
    private final List<Component> components = new ArrayList<>();
    private final Map<String, Range> componentRanges = new LinkedHashMap<>();
    private final Set<String> portalIds = new LinkedHashSet<>();
    private final PageContext context;
    private boolean renderingPortal;
    private boolean renderingWidget;
    private int componentSequence;

    private HtmlRenderer(PageContext context) {
        this.context = context;
    }

    /** Renders a node tree.
     * @param node root node
     * @param context page context
     * @return rendered HTML and bindings */
    public static RenderedTree render(Node node, PageContext context) {
        var renderer = new HtmlRenderer(context);
        renderer.append(node);
        var html = renderer.output.toString();
        var componentHtml = new LinkedHashMap<String, String>();
        renderer.componentRanges.forEach((id, range) -> componentHtml.put(id, html.substring(range.start(), range.end())));
        return new RenderedTree(html, renderer.actions, renderer.components, componentHtml);
    }

    /** Escapes a value for an HTML text context.
     * @param value unescaped value
     * @return escaped value */
    public static String escapeText(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** Escapes a value for a quoted HTML attribute context.
     * @param value unescaped value
     * @return escaped value */
    public static String escapeAttribute(String value) {
        return escapeText(value)
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void append(Node node) {
        if (renderingWidget && !(node instanceof TextNode || node instanceof FragmentNode
                || node instanceof Element || node instanceof OptimizedImage)) {
            throw new IllegalStateException("Widget fallback must contain only static HTML, text, or images");
        }
        switch (node) {
            case TextNode text -> output.append(escapeText(text.value()));
            case RawNode raw -> output.append(raw.value());
            case FragmentNode fragment -> fragment.children().forEach(this::append);
            case Element element -> append(element);
            case OptimizedImage image -> append(image.element());
            case Portal portal -> appendPortal(portal);
            case Modal modal -> append(modal.portal());
            case Component component -> {
                components.add(component);
                var id = "c" + componentSequence++;
                var rendered = component.render(context);
                if (rendered instanceof Element element) {
                    var start = output.length();
                    append(element, id);
                    componentRanges.put(id, new Range(start, output.length()));
                } else {
                    append(rendered);
                }
            }
            case ErrorBoundary boundary -> appendBoundary(boundary);
            default -> throw new IllegalArgumentException("Unknown Roots node type: " + node.getClass().getName());
        }
    }

    private void appendPortal(Portal portal) {
        if (renderingPortal) {
            throw new IllegalStateException("Portals cannot be nested");
        }
        if (!portalIds.add(portal.id())) {
            throw new IllegalStateException("Duplicate portal ID in one render: " + portal.id());
        }
        output.append("<template data-roots-portal=\"")
                .append(escapeAttribute(portal.id()))
                .append("\">");
        renderingPortal = true;
        try {
            append(portal.children());
        } finally {
            renderingPortal = false;
        }
        output.append("</template>");
    }

    private void appendBoundary(ErrorBoundary boundary) {
        var outputLength = output.length();
        var actionNames = List.copyOf(actions.keySet());
        var componentCount = components.size();
        var rangeIds = Set.copyOf(componentRanges.keySet());
        var sequence = componentSequence;
        var existingPortalIds = Set.copyOf(portalIds);
        try {
            append(boundary.children());
        } catch (Throwable error) {
            output.setLength(outputLength);
            actions.keySet().removeIf(name -> !actionNames.contains(name));
            while (components.size() > componentCount) {
                components.removeLast();
            }
            componentRanges.keySet().removeIf(id -> !rangeIds.contains(id));
            componentSequence = sequence;
            portalIds.removeIf(id -> !existingPortalIds.contains(id));
            append(boundary.fallback(error, context));
        }
    }

    private void append(Element element) {
        append(element, null);
    }

    private void append(Element element, String componentId) {
        var widget = element.attributes().containsKey("data-roots-widget");
        if (renderingWidget && (widget || !element.events().isEmpty()
                || Set.of("input", "textarea", "select", "button", "form", "a", "script", "template").contains(element.tag())
                || element.attributes().containsKey("contenteditable"))) {
            throw new IllegalStateException("Widget fallback cannot contain nested widgets, scripts, or interactive controls");
        }
        if (widget) {
            Element.validateWidgetModule(element.attributes().get("data-roots-widget"));
            var key = element.attributes().get("data-roots-key");
            if (key == null || key.isBlank() || key.length() > 256
                    || !Set.of("div", "span", "section", "article", "aside", "figure", "main").contains(element.tag())) {
                throw new IllegalStateException("Widgets require a stable key and a non-interactive HTML container");
            }
        }
        output.append('<').append(element.tag());
        if (componentId != null) {
            output.append(" data-roots-component=\"")
                    .append(componentId)
                    .append('"');
        }
        element.attributes().forEach((name, value) -> {
            output.append(' ').append(name);
            if (!value.isEmpty()) {
                var rendered = URL_ATTRIBUTES.contains(name)
                        ? context.url(value)
                        : name.equals("srcset") ? mountSourceSet(value) : value;
                output.append("=\"").append(escapeAttribute(rendered)).append('"');
            }
        });
        element.events().forEach((event, binding) -> {
            var previous = actions.putIfAbsent(binding.name(), binding.action());
            if (previous != null && !Actions.sameBinding(previous, binding.action())) {
                throw new IllegalStateException("Duplicate action name in one render: " + binding.name());
            }
            output.append(" data-roots-on-")
                    .append(event)
                    .append("=\"")
                    .append(escapeAttribute(binding.name()))
                    .append('"');
        });
        if (!element.optimisticEffects().isEmpty()) {
            if (element.events().isEmpty()) {
                throw new IllegalStateException("Optimistic effects require a server action on the same element");
            }
            output.append(" data-roots-optimistic=\"")
                    .append(escapeAttribute(optimisticJson(element.optimisticEffects())))
                    .append('"');
        }
        output.append('>');
        if (!Element.VOID_TAGS.contains(element.tag())) {
            var previousWidget = renderingWidget;
            renderingWidget |= widget;
            try {
                element.children().forEach(this::append);
            } finally {
                renderingWidget = previousWidget;
            }
            output.append("</").append(element.tag()).append('>');
        }
    }

    private String mountSourceSet(String value) {
        return java.util.Arrays.stream(value.split(","))
                .map(candidate -> {
                    var trimmed = candidate.trim();
                    var whitespace = trimmed.indexOf(' ');
                    var url = whitespace < 0 ? trimmed : trimmed.substring(0, whitespace);
                    var descriptor = whitespace < 0 ? "" : trimmed.substring(whitespace);
                    return context.url(url) + descriptor;
                })
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String optimisticJson(List<com.chaplin.roots.OptimisticEffect> effects) {
        var result = new StringBuilder("[");
        for (var index = 0; index < effects.size(); index++) {
            if (index > 0) {
                result.append(',');
            }
            var effect = effects.get(index);
            result.append("{\"type\":\"").append(effect.type()).append("\",\"target\":")
                    .append(jsonString(effect.target()));
            if (effect.value() != null) {
                result.append(",\"value\":").append(jsonString(effect.value()));
            }
            result.append('}');
        }
        return result.append(']').toString();
    }

    private static String jsonString(String value) {
        var result = new StringBuilder(value.length() + 8).append('"');
        for (var character : value.toCharArray()) {
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append("\\u%04x".formatted((int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private record Range(int start, int end) {
    }
}
