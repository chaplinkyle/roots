package com.chaplin.roots.testapp.pages.cookies;

import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.ResponseCookie;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;
import com.chaplin.roots.testapp.FixtureProblem;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.h1;

@PageMetadata(title = "Cookie fixture")
public final class Page implements com.chaplin.roots.Page {
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
