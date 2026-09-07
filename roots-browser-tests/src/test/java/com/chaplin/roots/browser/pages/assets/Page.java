package com.chaplin.roots.browser.pages.assets;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageFont;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.image;
import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.main;

@PageMetadata(
        title = "Roots asset fixture",
        fonts = @PageFont(family = "Roots Browser Sans", source = "/fonts/browser.woff2", preload = true)
)
public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main(
                image("/images/browser.png", "Blue browser test rectangle", 8, 4)
                        .widths(4, 8)
                        .sizes("4px")
                        .quality(80)
                        .attr("id", "optimized-image"),
                link("/", "Home").id("asset-home")
        );
    }
}
