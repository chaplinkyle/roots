package com.chaplin.roots.errorpageapp.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.h1;

public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return h1("Home");
    }
}
