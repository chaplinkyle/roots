package com.chaplin.roots.ambiguousapp.pages.two;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.Route;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.main;

@Route("/items/{name}")
public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main("two");
    }
}
