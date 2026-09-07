package com.chaplin.roots.browser.pages;

import com.chaplin.roots.Component;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;

final class ScopedCounter implements Component {
    private int count;

    @Override
    public Node render(PageContext context) {
        return button("Scoped ", count).id("scoped-counter").onClick(this, "increment");
    }

    @ServerAction
    private void increment() {
        count++;
    }
}
