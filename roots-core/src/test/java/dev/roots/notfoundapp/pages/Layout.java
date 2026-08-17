package dev.roots.notfoundapp.pages;

import dev.roots.PageContext;
import dev.roots.html.Node;

import static dev.roots.html.Html.main;

public final class Layout implements dev.roots.Layout {
    @Override
    public Node render(PageContext context, Node children) {
        return main(children).data("not-found-layout", "true");
    }
}
