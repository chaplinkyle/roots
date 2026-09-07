package ${package}.pages;

import ${package}.components.Counter;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.p;
import static com.chaplin.roots.html.Html.section;

@PageMetadata(
        title = "${artifactId} · Roots",
        description = "A full-stack Java application built with Roots.",
        stylesheets = "/app.css",
        openGraphType = "website"
)
public final class Page implements com.chaplin.roots.Page {
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
