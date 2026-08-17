package dev.roots.browser.pages;

import dev.roots.HeadMetadata;
import dev.roots.OpenGraphMetadata;
import dev.roots.PageContext;
import dev.roots.html.Node;

import static dev.roots.html.Html.main;

public final class Layout implements dev.roots.Layout {
    @Override
    public HeadMetadata headMetadata(PageContext context) {
        return new HeadMetadata()
                .withRobots("index, follow")
                .withThemeColor("#0b253f")
                .withOpenGraph(new OpenGraphMetadata().withSiteName("Roots Browser"));
    }

    @Override
    public Node render(PageContext context, Node children) {
        return main(children).id("browser-fixture");
    }
}
