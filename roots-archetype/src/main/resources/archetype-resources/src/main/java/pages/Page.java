package ${package}.pages;

import ${package}.components.Counter;
import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.html.Node;

import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.p;
import static dev.roots.html.Html.section;

@PageMetadata(
        title = "${artifactId} · Roots",
        description = "A full-stack Java application built with Roots.",
        stylesheets = "/app.css"
)
public final class Page implements dev.roots.Page {
    private final Counter counter = new Counter();

    @Override
    public Node render(PageContext context) {
        return section(
                h1("Java is the full stack."),
                p("This page, component state, event handler, router, and server are Java."),
                counter
        ).className("hero");
    }
}
