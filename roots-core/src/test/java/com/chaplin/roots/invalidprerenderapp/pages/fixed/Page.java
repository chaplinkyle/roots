package com.chaplin.roots.invalidprerenderapp.pages.fixed;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.main;

public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main("fixed");
    }
}
