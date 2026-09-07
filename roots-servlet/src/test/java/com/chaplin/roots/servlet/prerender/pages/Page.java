package com.chaplin.roots.servlet.prerender.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.Prerender;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.main;

@Prerender
@PageMetadata(title = "Mounted static", stylesheets = "/servlet.css")
public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main(link("/", "Static home"));
    }
}
