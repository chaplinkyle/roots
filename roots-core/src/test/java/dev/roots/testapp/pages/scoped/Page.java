package dev.roots.testapp.pages.scoped;

import dev.roots.Component;
import dev.roots.PageContext;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.h1;

public final class Page implements dev.roots.Page {
    private final Counter counter = new Counter();
    private final String dependency;

    public Page(String dependency) {
        this.dependency = dependency;
    }

    @Override
    public Node render(PageContext context) {
        return div(h1("Outside " + dependency), counter);
    }

    private static final class Counter implements Component {
        private int value;

        @Override
        public Node render(PageContext context) {
            return button("Scoped count ", value)
                    .onClick(this, "increment")
                    .onDoubleClick(this, "noop");
        }

        @ServerAction
        private void increment() {
            value++;
        }

        @ServerAction
        private void noop() {
        }
    }
}
