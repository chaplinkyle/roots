package com.chaplin.roots.html;

import com.chaplin.roots.Action;
import com.chaplin.roots.Component;

import java.util.List;
import java.util.Map;

/** Result of rendering a Roots node tree.
 * @param html rendered HTML
 * @param actions action bindings keyed by wire name
 * @param components rendered component instances
 * @param componentHtml rendered element-root component boundaries keyed by occurrence ID */
public record RenderedTree(
        String html,
        Map<String, Action> actions,
        List<Component> components,
        Map<String, String> componentHtml
) {
    /** Copies the action, component, and boundary collections. */
    public RenderedTree {
        actions = Map.copyOf(actions);
        components = List.copyOf(components);
        componentHtml = Map.copyOf(componentHtml);
    }

    /**
     * Creates a rendered tree without component boundary metadata.
     *
     * @param html rendered HTML
     * @param actions action bindings keyed by wire name
     * @param components rendered component instances
     */
    public RenderedTree(String html, Map<String, Action> actions, List<Component> components) {
        this(html, actions, components, Map.of());
    }
}
