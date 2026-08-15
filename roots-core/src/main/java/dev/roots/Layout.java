package dev.roots;

import dev.roots.html.Node;

public interface Layout {
    Node render(PageContext context, Node children);

    default void onMount(PageContext context) {
    }

    default void onUnmount(PageContext context) {
    }
}
