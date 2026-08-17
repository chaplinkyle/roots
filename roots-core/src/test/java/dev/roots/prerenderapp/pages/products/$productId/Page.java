package dev.roots.prerenderapp.pages.products.$productId;

import dev.roots.PageContext;
import dev.roots.annotation.Prerender;
import dev.roots.html.Node;
import dev.roots.prerenderapp.ProductPaths;

import static dev.roots.html.Html.main;

@Prerender(paths = ProductPaths.class)
public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main("Static product " + context.parameter("productId"));
    }
}
