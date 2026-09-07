package com.chaplin.roots.ambiguousapp.pages.one;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.Route;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.main;

@Route("/items/{id}")
public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main("one");
    }
}
