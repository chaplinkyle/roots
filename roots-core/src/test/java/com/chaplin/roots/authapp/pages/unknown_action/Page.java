package com.chaplin.roots.authapp.pages.unknown_action;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;

public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return button("Unavailable action").onClick(this, "unavailable");
    }

    @Authorize("not-registered")
    @ServerAction
    private void unavailable() {
    }
}
