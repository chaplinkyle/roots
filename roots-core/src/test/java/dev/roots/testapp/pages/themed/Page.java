package dev.roots.testapp.pages.themed;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.html.Node;

import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.link;

@PageMetadata(title = "Themed fixture", stylesheets = "/theme.css")
public final class Page implements dev.roots.Page {
    private final String greeting;

    public Page(String greeting) {
        this.greeting = greeting;
    }

    @Override
    public Node render(PageContext context) {
        return h1("Themed " + greeting, link("/", "Home"));
    }
}
