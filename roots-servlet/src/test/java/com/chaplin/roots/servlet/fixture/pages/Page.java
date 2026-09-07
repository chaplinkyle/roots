package com.chaplin.roots.servlet.fixture.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.ResponseCookie;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.link;

public final class Page implements com.chaplin.roots.Page {
    private int count;
    private String actionClient = "none";
    private String actionCookie = "none";

    @Override
    public Node render(PageContext context) {
        return div(
                h1("Servlet transport"),
                div("Mount " + context.mountPath()),
                link("/", "Home"),
                div("Identity " + context.identity().map(identity -> identity.name()).orElse("anonymous")),
                div("Client " + context.connection().clientAddressText()),
                div("Origin " + context.connection().origin()),
                div("Forwarded " + context.connection().forwarded()),
                div("Action client " + actionClient),
                div("Render cookie " + context.cookie("servlet-preference").orElse("missing")),
                div("Action cookie " + actionCookie),
                button("Count ", count).id("count").onClick(this, "increment")
        );
    }

    @ServerAction
    private void increment(ActionEvent event) {
        count++;
        actionClient = event.connection().clientAddressText();
        actionCookie = event.cookie("servlet-preference").orElse("missing");
        event.setCookie(ResponseCookie.builder("servlet-result", "seen-" + actionCookie)
                .path(event.cookiePath())
                .httpOnly(false)
                .build());
    }
}
