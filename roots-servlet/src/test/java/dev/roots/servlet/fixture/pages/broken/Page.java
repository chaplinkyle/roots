package dev.roots.servlet.fixture.pages.broken;

import dev.roots.PageContext;
import dev.roots.html.Node;

public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        throw new IllegalStateException("expected Servlet page failure");
    }
}
