package com.chaplin.roots;

import com.chaplin.roots.html.Node;

/**
 * A reusable server-side UI component. Components compose directly anywhere a
 * {@link Node} is accepted. Keep a component in a page field when its Java
 * instance should retain state between browser events.
 */
@FunctionalInterface
public interface Component extends Node {
    /** Renders the current component state.
     * @param context page and session context
     * @return rendered node tree */
    Node render(PageContext context);

    /** Runs after the component first enters a live view.
     * @param context page and session context */
    default void onMount(PageContext context) {
    }

    /** Runs when the component leaves a live view.
     * @param context page and session context */
    default void onUnmount(PageContext context) {
    }
}
