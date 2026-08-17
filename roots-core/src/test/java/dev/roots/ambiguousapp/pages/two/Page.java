package dev.roots.ambiguousapp.pages.two;

import dev.roots.PageContext;
import dev.roots.annotation.Route;
import dev.roots.html.Node;

import static dev.roots.html.Html.main;

@Route("/items/{name}")
public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main("two");
    }
}
