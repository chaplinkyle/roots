package ${package}.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.p;
import static com.chaplin.roots.html.Html.section;

@PageMetadata(
        title = "Page not found",
        description = "The requested page could not be found.",
        stylesheets = "/app.css"
)
public final class NotFound implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return section(
                h1("That page does not exist."),
                p("No page matches ", context.path(), "."),
                link("/", "Return home")
        ).className("hero");
    }
}
