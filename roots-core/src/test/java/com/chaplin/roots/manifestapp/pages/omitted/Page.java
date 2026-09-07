package com.chaplin.roots.manifestapp.pages.omitted;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.main;

public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main("scanner-only page");
    }
}
