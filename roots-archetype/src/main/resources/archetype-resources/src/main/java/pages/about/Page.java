package ${package}.pages.about;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.html.Node;

import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.p;
import static dev.roots.html.Html.section;

@PageMetadata(title = "About · ${artifactId}", stylesheets = "/app.css")
public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return section(h1("About"), p("This route comes from pages/about/Page.java."));
    }
}
