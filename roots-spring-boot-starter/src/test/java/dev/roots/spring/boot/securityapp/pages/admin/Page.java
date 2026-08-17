package dev.roots.spring.boot.securityapp.pages.admin;

import dev.roots.ActionEvent;
import dev.roots.PageContext;
import dev.roots.State;
import dev.roots.annotation.Authorize;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;

@Authorize("admin")
public final class Page implements dev.roots.Page {
    private final State<String> actionIdentity = State.of("none");

    @Override
    public Node render(PageContext context) {
        return div(
                div("Page identity: " + context.identity().map(identity -> identity.name()).orElse("anonymous")),
                div("Action identity: " + actionIdentity.get()),
                button("Capture identity").onClick(this, "capture")
        );
    }

    @ServerAction
    private void capture(ActionEvent event) {
        actionIdentity.set(event.identity().map(identity -> identity.name()).orElse("anonymous"));
    }
}
