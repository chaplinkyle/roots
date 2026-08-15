package dev.roots.html;

import dev.roots.Action;
import dev.roots.Component;
import dev.roots.ErrorBoundary;
import dev.roots.PageContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HtmlRenderer {
    private final StringBuilder output = new StringBuilder();
    private final Map<String, Action> actions = new LinkedHashMap<>();
    private final List<Component> components = new ArrayList<>();
    private final PageContext context;

    private HtmlRenderer(PageContext context) {
        this.context = context;
    }

    public static RenderedTree render(Node node, PageContext context) {
        var renderer = new HtmlRenderer(context);
        renderer.append(node);
        return new RenderedTree(renderer.output.toString(), renderer.actions, renderer.components);
    }

    public static String escapeText(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public static String escapeAttribute(String value) {
        return escapeText(value)
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void append(Node node) {
        switch (node) {
            case TextNode text -> output.append(escapeText(text.value()));
            case RawNode raw -> output.append(raw.value());
            case FragmentNode fragment -> fragment.children().forEach(this::append);
            case Element element -> append(element);
            case Component component -> {
                components.add(component);
                append(component.render(context));
            }
            case ErrorBoundary boundary -> appendBoundary(boundary);
            default -> throw new IllegalArgumentException("Unknown Roots node type: " + node.getClass().getName());
        }
    }

    private void appendBoundary(ErrorBoundary boundary) {
        var outputLength = output.length();
        var actionNames = List.copyOf(actions.keySet());
        var componentCount = components.size();
        try {
            append(boundary.children());
        } catch (Throwable error) {
            output.setLength(outputLength);
            actions.keySet().removeIf(name -> !actionNames.contains(name));
            while (components.size() > componentCount) {
                components.removeLast();
            }
            append(boundary.fallback(error, context));
        }
    }

    private void append(Element element) {
        output.append('<').append(element.tag());
        element.attributes().forEach((name, value) -> {
            output.append(' ').append(name);
            if (!value.isEmpty()) {
                output.append("=\"").append(escapeAttribute(value)).append('"');
            }
        });
        element.events().forEach((event, binding) -> {
            var previous = actions.putIfAbsent(binding.name(), binding.action());
            if (previous != null && previous != binding.action()) {
                throw new IllegalStateException("Duplicate action name in one render: " + binding.name());
            }
            output.append(" data-roots-on-")
                    .append(event)
                    .append("=\"")
                    .append(escapeAttribute(binding.name()))
                    .append('"');
        });
        output.append('>');
        if (!Element.VOID_TAGS.contains(element.tag())) {
            element.children().forEach(this::append);
            output.append("</").append(element.tag()).append('>');
        }
    }
}
