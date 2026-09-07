package ${package}.pages.about;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.Prerender;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.p;
import static com.chaplin.roots.html.Html.section;

@PageMetadata(title = "About · ${artifactId}", stylesheets = "/app.css")
@Prerender
public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return section(h1("About"), p("This route comes from pages/about/Page.java."));
    }
}
