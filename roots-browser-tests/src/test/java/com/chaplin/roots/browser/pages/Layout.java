package com.chaplin.roots.browser.pages;

import com.chaplin.roots.HeadMetadata;
import com.chaplin.roots.OpenGraphMetadata;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.main;

public final class Layout implements com.chaplin.roots.Layout {
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
