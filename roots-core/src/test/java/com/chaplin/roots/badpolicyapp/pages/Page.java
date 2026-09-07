package com.chaplin.roots.badpolicyapp.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.h1;

@Authorize("missing")
public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return h1("This route must not start without its policy");
    }
}
