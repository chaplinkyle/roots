package com.chaplin.roots.authapp.pages.admin;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.State;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;

@Authorize("staff")
public final class Page implements com.chaplin.roots.Page {
    private final State<Integer> count = State.of(0);

    @Override
    public Node render(PageContext context) {
        return div(
                div("Count " + count.get()).id("authorization-count"),
                button("Increment").onClick(this, "increment")
        );
    }

    @Authorize("write")
    @ServerAction
    private void increment() {
        count.update(value -> value + 1);
    }
}
