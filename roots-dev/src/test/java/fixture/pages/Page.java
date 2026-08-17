package fixture.pages;

import dev.roots.PageContext;
import dev.roots.html.Node;

import static dev.roots.html.Html.h1;

public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return h1("Development fixture");
    }
}
