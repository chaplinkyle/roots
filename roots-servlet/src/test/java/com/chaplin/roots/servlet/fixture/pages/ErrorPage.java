package com.chaplin.roots.servlet.fixture.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.main;

@PageMetadata(title = "Servlet application error")
public final class ErrorPage implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main(h1("Servlet custom 500"));
    }
}
