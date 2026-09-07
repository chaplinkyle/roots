package com.chaplin.roots.servlet.fixture.pages.broken;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        throw new IllegalStateException("expected Servlet page failure");
    }
}
