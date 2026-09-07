package com.chaplin.roots.load.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.main;

@PageMetadata(title = "Roots load fixture")
public final class Page implements com.chaplin.roots.Page {
    private int count;

    @Override
    public Node render(PageContext context) {
        return main(
                h1("Load fixture"),
                button("Count " + count).id("counter").onClick(this, "increment")
        );
    }

    @ServerAction
    private void increment() {
        count++;
    }
}
