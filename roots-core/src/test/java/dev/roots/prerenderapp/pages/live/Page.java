package dev.roots.prerenderapp.pages.live;

import dev.roots.PageContext;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;

public final class Page implements dev.roots.Page {
    private int count;

    @Override
    public Node render(PageContext context) {
        return button("Live " + count).onClick(this, "increment");
    }

    @ServerAction
    private void increment() {
        count++;
    }
}
