package dev.roots;

import dev.roots.html.Node;

/**
 * A reusable server-side UI component. Components compose directly anywhere a
 * {@link Node} is accepted. Keep a component in a page field when its Java
 * instance should retain state between browser events.
 */
@FunctionalInterface
public interface Component extends Node {
    Node render(PageContext context);

    default void onMount(PageContext context) {
    }

    default void onUnmount(PageContext context) {
    }
}
