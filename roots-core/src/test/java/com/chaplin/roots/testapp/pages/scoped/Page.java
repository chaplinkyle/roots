package com.chaplin.roots.testapp.pages.scoped;

import com.chaplin.roots.Component;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.h1;

public final class Page implements com.chaplin.roots.Page {
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
