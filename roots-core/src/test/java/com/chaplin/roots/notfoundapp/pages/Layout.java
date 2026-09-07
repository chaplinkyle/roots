package com.chaplin.roots.notfoundapp.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.main;

public final class Layout implements com.chaplin.roots.Layout {
    @Override
    public Node render(PageContext context, Node children) {
        return main(children).data("not-found-layout", "true");
    }
}
