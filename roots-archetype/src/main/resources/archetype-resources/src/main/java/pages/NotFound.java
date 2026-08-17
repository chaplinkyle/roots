package ${package}.pages;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.html.Node;

import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.link;
import static dev.roots.html.Html.p;
import static dev.roots.html.Html.section;

@PageMetadata(
        title = "Page not found",
        description = "The requested page could not be found.",
        stylesheets = "/app.css"
)
public final class NotFound implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return section(
                h1("That page does not exist."),
                p("No page matches ", context.path(), "."),
                link("/", "Return home")
        ).className("hero");
    }
}
