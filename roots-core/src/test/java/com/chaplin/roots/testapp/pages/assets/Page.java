package com.chaplin.roots.testapp.pages.assets;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageFont;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.image;
import static com.chaplin.roots.html.Html.main;

@PageMetadata(
        title = "Asset fixture",
        fonts = @PageFont(family = "Roots Sans", source = "/fonts/roots.woff2", preload = true)
)
public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main(image("/images/hero.png", "Blue test image", 8, 4)
                .widths(4, 8)
                .quality(70));
    }
}
