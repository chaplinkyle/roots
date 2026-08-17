package dev.roots.errorpageapp.pages.broken;

import dev.roots.PageContext;
import dev.roots.html.Node;

public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        throw new IllegalStateException("sensitive broken-page detail");
    }
}
