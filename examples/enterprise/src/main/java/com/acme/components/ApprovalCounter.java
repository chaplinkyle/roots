package com.acme.components;

import dev.roots.Component;
import dev.roots.PageContext;
import dev.roots.annotation.ServerAction;
import dev.roots.annotation.ViewComponent;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.p;
import static dev.roots.html.Html.span;
import static dev.roots.html.Html.strong;

@ViewComponent("approval-counter")
public final class ApprovalCounter implements Component {
    private int approved = 18;
    private int remaining = 6;

    @Override
    public Node render(PageContext context) {
        return div(
                div(
                        span("LIVE JAVA COMPONENT").className("eyebrow signal"),
                        strong(approved).className("approval-number"),
                        p(remaining == 0
                                ? "Queue cleared. Every access review has an owner."
                                : remaining + " access reviews still need a decision.")
                                .className("approval-copy")
                ),
                button(remaining == 0 ? "Queue complete" : "Approve next")
                        .type("button")
                        .className("button primary")
                        .attr("disabled", remaining == 0)
                        .onClick(this, "approve")
        ).className("approval-component");
    }

    @ServerAction("approve")
    private void approveNext() {
        if (remaining > 0) {
            approved++;
            remaining--;
        }
    }
}
