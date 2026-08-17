package dev.roots.browser.pages.fast;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.html.Node;

import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.link;
import static dev.roots.html.Html.main;
import static dev.roots.html.Html.p;

@PageMetadata(title = "Roots fast fixture", stylesheets = "/base.css")
public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main(
                h1("Fast page").id("fast-page"),
                link("/", "Home").id("fast-home"),
                div().className("fragment-spacer").aria("hidden", "true"),
                p("Fast fragment target").id("fast-fragment-target"),
                div().className("fragment-spacer").aria("hidden", "true")
        );
    }
}
