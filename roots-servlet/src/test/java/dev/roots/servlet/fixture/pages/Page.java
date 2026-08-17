package dev.roots.servlet.fixture.pages;

import dev.roots.PageContext;
import dev.roots.ActionEvent;
import dev.roots.ResponseCookie;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.link;

public final class Page implements dev.roots.Page {
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
