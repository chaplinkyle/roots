package dev.roots.servlet.fixture.pages;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.html.Node;

import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.main;

@PageMetadata(title = "Servlet application error")
public final class ErrorPage implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main(h1("Servlet custom 500"));
    }
}
