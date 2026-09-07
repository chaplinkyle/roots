package com.chaplin.roots.prerenderapp.pages.products.$productId;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.Prerender;
import com.chaplin.roots.html.Node;
import com.chaplin.roots.prerenderapp.ProductPaths;

import static com.chaplin.roots.html.Html.main;

@Prerender(paths = ProductPaths.class)
public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main("Static product " + context.parameter("productId"));
    }
}
