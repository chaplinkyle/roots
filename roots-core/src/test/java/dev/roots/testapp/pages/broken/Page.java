package dev.roots.testapp.pages.broken;

import dev.roots.PageContext;
import dev.roots.html.Node;
import dev.roots.testapp.FixtureProblem;

public final class Page implements dev.roots.Page {
    public Page(String ignored) {
    }

    @Override
    public Node render(PageContext context) {
        throw new FixtureProblem("broken page fixture");
    }
}
