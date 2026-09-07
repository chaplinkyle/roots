package com.chaplin.roots.testapp.pages.themed;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.link;

@PageMetadata(title = "Themed fixture", stylesheets = "/theme.css")
public final class Page implements com.chaplin.roots.Page {
    private final String greeting;

    public Page(String greeting) {
        this.greeting = greeting;
    }

    @Override
    public Node render(PageContext context) {
        return h1("Themed " + greeting, link("/", "Home"));
    }
}
