package dev.roots.browser.pages;

import dev.roots.Component;
import dev.roots.PageContext;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;

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
