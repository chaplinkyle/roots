package dev.roots;

import dev.roots.html.Node;

/** Wraps a page in convention-discovered shared UI. */
public interface Layout {
    /** Supplies shared canonical, robots, theme, and social metadata.
     * Nested layouts are merged from outermost to innermost and page values take precedence.
     * @param context page and session context
     * @return shared extended document metadata */
    default HeadMetadata headMetadata(PageContext context) {
        return HeadMetadata.EMPTY;
    }

    /** Renders the layout around page content.
     * @param context page and session context
     * @param children nested page or layout
     * @return rendered layout tree */
    Node render(PageContext context, Node children);

    /** Runs after the layout first enters a live view.
     * @param context page and session context */
    default void onMount(PageContext context) {
    }

    /** Runs when the layout leaves a live view.
     * @param context page and session context */
    default void onUnmount(PageContext context) {
    }
}
