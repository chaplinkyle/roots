package ${package}.pages;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.html.Node;

import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.link;
import static dev.roots.html.Html.main;
import static dev.roots.html.Html.p;

@PageMetadata(
        title = "Application error",
        description = "The request could not be completed.",
        stylesheets = "/app.css"
)
public final class ErrorPage implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main(
                h1("Something went wrong."),
                p("Support reference: ", context.traceContext().traceId()),
                link("/", "Return home")
        ).className("hero");
    }
}
