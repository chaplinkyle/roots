package com.chaplin.roots.testapp.pages.secure;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.h1;

public final class Page implements com.chaplin.roots.Page {
    private final String greeting;

    public Page(String greeting) {
        this.greeting = greeting;
    }

    @Override
    public Node render(PageContext context) {
        return h1("Secure " + greeting);
    }
}
