package dev.roots.testapp.pages.assets;

import dev.roots.PageContext;
import dev.roots.annotation.PageFont;
import dev.roots.annotation.PageMetadata;
import dev.roots.html.Node;

import static dev.roots.html.Html.image;
import static dev.roots.html.Html.main;

@PageMetadata(
        title = "Asset fixture",
        fonts = @PageFont(family = "Roots Sans", source = "/fonts/roots.woff2", preload = true)
)
public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main(image("/images/hero.png", "Blue test image", 8, 4)
                .widths(4, 8)
                .quality(70));
    }
}
