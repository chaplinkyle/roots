package dev.roots.testapp.pages.cookies;

import dev.roots.ActionEvent;
import dev.roots.PageContext;
import dev.roots.ResponseCookie;
import dev.roots.annotation.PageMetadata;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;
import dev.roots.testapp.FixtureProblem;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.h1;

@PageMetadata(title = "Cookie fixture")
public final class Page implements dev.roots.Page {
    private final String dependency;
    private String observed = "none";

    public Page(String dependency) {
        this.dependency = dependency;
    }

    @Override
    public Node render(PageContext context) {
        return div(
                h1("Cookie fixture"),
                div("render=", context.cookie("theme").orElse("missing"), "; observed=", observed,
                        "; dependency=", dependency).id("cookie-state"),
                button("Set cookie").id("cookie-set").onClick(this, "setCookie"),
                button("Delete cookie").id("cookie-delete").onClick(this, "deleteCookie"),
                button("Fail cookie").id("cookie-fail").onClick(this, "failCookie")
        );
    }

    @ServerAction
    private void setCookie(ActionEvent event) {
        observed = event.cookie("theme").orElse("missing");
        event.setCookie(ResponseCookie.builder("action-cookie", "seen-" + observed)
                .path(event.cookiePath())
                .httpOnly(false)
                .build());
    }

    @ServerAction
    private void deleteCookie(ActionEvent event) {
        event.deleteCookie("action-cookie");
    }

    @ServerAction
    private void failCookie(ActionEvent event) {
        event.setCookie(ResponseCookie.builder("must-not-commit", "value").build());
        throw new FixtureProblem("cookie action failed");
    }
}
