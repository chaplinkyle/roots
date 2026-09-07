package com.chaplin.roots.prerenderapp.pages.live;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;

public final class Page implements com.chaplin.roots.Page {
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
