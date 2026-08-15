package ${package}.pages;

import dev.roots.PageContext;
import dev.roots.html.Node;

import static dev.roots.html.Html.div;
import static dev.roots.html.Html.header;
import static dev.roots.html.Html.link;
import static dev.roots.html.Html.main;
import static dev.roots.html.Html.nav;
import static dev.roots.html.Html.strong;

public final class Layout implements dev.roots.Layout {
    @Override
    public Node render(PageContext context, Node children) {
        return div(
                header(strong("${artifactId}"), nav(link("/", "Home"), link("/about", "About"))),
                main(children)
        ).className("shell");
    }
}
