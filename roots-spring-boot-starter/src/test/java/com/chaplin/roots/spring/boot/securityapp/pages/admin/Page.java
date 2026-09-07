package com.chaplin.roots.spring.boot.securityapp.pages.admin;

import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.State;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;

@Authorize("admin")
public final class Page implements com.chaplin.roots.Page {
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
