package dev.roots.badpolicyapp.pages;

import dev.roots.PageContext;
import dev.roots.annotation.Authorize;
import dev.roots.html.Node;

import static dev.roots.html.Html.h1;

@Authorize("missing")
public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return h1("This route must not start without its policy");
    }
}
