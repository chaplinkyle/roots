package dev.roots.browser.pages.assets;

import dev.roots.PageContext;
import dev.roots.annotation.PageFont;
import dev.roots.annotation.PageMetadata;
import dev.roots.html.Node;

import static dev.roots.html.Html.image;
import static dev.roots.html.Html.link;
import static dev.roots.html.Html.main;

@PageMetadata(
        title = "Roots asset fixture",
        fonts = @PageFont(family = "Roots Browser Sans", source = "/fonts/browser.woff2", preload = true)
)
public final class Page implements dev.roots.Page {
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
