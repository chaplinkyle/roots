package ${package}.pages;

import com.chaplin.roots.HeadMetadata;
import com.chaplin.roots.OpenGraphMetadata;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.header;
import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.main;
import static com.chaplin.roots.html.Html.nav;
import static com.chaplin.roots.html.Html.strong;

public final class Layout implements com.chaplin.roots.Layout {
    @Override
    public HeadMetadata headMetadata(PageContext context) {
        return new HeadMetadata()
                .withRobots("index, follow")
                .withThemeColor("#17324d")
                .withOpenGraph(new OpenGraphMetadata().withSiteName("${artifactId}"));
    }

    @Override
    public Node render(PageContext context, Node children) {
        return div(
                header(strong("${artifactId}"), nav(link("/", "Home"), link("/about", "About"))),
                main(children)
        ).className("shell");
    }
}
