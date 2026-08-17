package dev.roots.load.pages;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.main;

@PageMetadata(title = "Roots load fixture")
public final class Page implements dev.roots.Page {
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
