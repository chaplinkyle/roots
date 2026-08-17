package dev.roots.traceapp.pages;

import dev.roots.ActionEvent;
import dev.roots.PageContext;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.main;
import static dev.roots.html.Html.p;

public final class Page implements dev.roots.Page {
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
