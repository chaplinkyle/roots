package com.chaplin.roots.malformedmanifestapp.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.main;

public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main("malformed manifest fallback must not run");
    }
}
