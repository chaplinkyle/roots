package com.chaplin.roots.browser.pages.fast;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.main;
import static com.chaplin.roots.html.Html.p;

@PageMetadata(title = "Roots fast fixture", stylesheets = "/base.css")
public final class Page implements com.chaplin.roots.Page {
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
