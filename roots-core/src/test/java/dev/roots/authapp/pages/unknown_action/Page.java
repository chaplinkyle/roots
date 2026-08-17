package dev.roots.authapp.pages.unknown_action;

import dev.roots.PageContext;
import dev.roots.annotation.Authorize;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;

public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return button("Unavailable action").onClick(this, "unavailable");
    }

    @Authorize("not-registered")
    @ServerAction
    private void unavailable() {
    }
}
