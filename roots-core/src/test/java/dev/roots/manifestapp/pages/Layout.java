package dev.roots.manifestapp.pages;

import dev.roots.PageContext;
import dev.roots.html.Node;

public final class Layout implements dev.roots.Layout {
    @Override
    public Node render(PageContext context, Node children) {
        return children;
    }
}
