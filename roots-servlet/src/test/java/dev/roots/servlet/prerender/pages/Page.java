package dev.roots.servlet.prerender.pages;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.annotation.Prerender;
import dev.roots.html.Node;

import static dev.roots.html.Html.link;
import static dev.roots.html.Html.main;

@Prerender
@PageMetadata(title = "Mounted static", stylesheets = "/servlet.css")
public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main(link("/", "Static home"));
    }
}
