package dev.roots.manifestapp.pages.omitted;

import dev.roots.PageContext;
import dev.roots.html.Node;

import static dev.roots.html.Html.main;

public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main("scanner-only page");
    }
}
