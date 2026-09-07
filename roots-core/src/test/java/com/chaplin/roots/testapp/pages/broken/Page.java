package com.chaplin.roots.testapp.pages.broken;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;
import com.chaplin.roots.testapp.FixtureProblem;

public final class Page implements com.chaplin.roots.Page {
    public Page(String ignored) {
    }

    @Override
    public Node render(PageContext context) {
        throw new FixtureProblem("broken page fixture");
    }
}
