package ${package}.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.main;
import static com.chaplin.roots.html.Html.p;

@PageMetadata(
        title = "Application error",
        description = "The request could not be completed.",
        stylesheets = "/app.css"
)
public final class ErrorPage implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main(
                h1("Something went wrong."),
                p("Support reference: ", context.traceContext().traceId()),
                link("/", "Return home")
        ).className("hero");
    }
}
