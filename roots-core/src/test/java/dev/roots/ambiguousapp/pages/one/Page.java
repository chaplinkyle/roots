package dev.roots.ambiguousapp.pages.one;

import dev.roots.PageContext;
import dev.roots.annotation.Route;
import dev.roots.html.Node;

import static dev.roots.html.Html.main;

@Route("/items/{id}")
public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main("one");
    }
}
