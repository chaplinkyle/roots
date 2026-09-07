package com.chaplin.roots.traceapp.pages;

import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.main;
import static com.chaplin.roots.html.Html.p;

public final class Page implements com.chaplin.roots.Page {
    private String actionSpan = "none";

    @Override
    public Node render(PageContext context) {
        return main(
                p("render-span=" + context.traceContext().spanId()),
                p("action-span=" + actionSpan),
                button("Trace action").onClick(this, "trace")
        );
    }

    @ServerAction
    private void trace(ActionEvent event) {
        actionSpan = event.traceContext().spanId();
    }
}
