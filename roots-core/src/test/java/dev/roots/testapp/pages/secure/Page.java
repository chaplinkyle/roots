package dev.roots.testapp.pages.secure;

import dev.roots.PageContext;
import dev.roots.html.Node;

import static dev.roots.html.Html.h1;

public final class Page implements dev.roots.Page {
    private final String greeting;

    public Page(String greeting) {
        this.greeting = greeting;
    }

    @Override
    public Node render(PageContext context) {
        return h1("Secure " + greeting);
    }
}
