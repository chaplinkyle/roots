package dev.roots.html;

import dev.roots.Action;
import dev.roots.Component;

import java.util.List;
import java.util.Map;

public record RenderedTree(String html, Map<String, Action> actions, List<Component> components) {
    public RenderedTree {
        actions = Map.copyOf(actions);
        components = List.copyOf(components);
    }
}
