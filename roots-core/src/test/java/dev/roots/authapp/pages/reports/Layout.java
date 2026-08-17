package dev.roots.authapp.pages.reports;

import dev.roots.PageContext;
import dev.roots.annotation.Authorize;
import dev.roots.html.Node;

import static dev.roots.html.Html.main;

@Authorize("staff")
public final class Layout implements dev.roots.Layout {
    @Override
    public Node render(PageContext context, Node children) {
        return main(children).data("authorized-layout", "true");
    }
}
