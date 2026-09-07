package com.acme.components;

import com.chaplin.roots.Component;
import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.OptimisticEffect;
import com.chaplin.roots.Ref;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.annotation.ViewComponent;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.p;
import static com.chaplin.roots.html.Html.span;
import static com.chaplin.roots.html.Html.strong;

@ViewComponent("approval-counter")
public final class ApprovalCounter implements Component {
    private int approved = 18;
    private int remaining = 6;
    private final Ref approveButton = Ref.create();

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
                        .ref(approveButton)
                        .attr("disabled", remaining == 0)
                        .optimistic(
                                OptimisticEffect.text(approveButton, "Approving..."),
                                OptimisticEffect.disable(approveButton)
                        )
                        .onClick(this, "approve")
        ).className("approval-component").pendingScope();
    }

    @Authorize("initialized-view")
    @ServerAction("approve")
    private void approveNext(ActionEvent event) {
        if (remaining > 0) {
            approved++;
            remaining--;
            event.cache().invalidateTag("operations-dashboard");
            event.viewTransition();
        }
    }
}
