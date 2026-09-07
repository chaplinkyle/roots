package com.chaplin.roots.notfoundapp.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.h1;

@PageMetadata(title = "Custom not found", description = "The requested page does not exist")
public final class NotFound implements com.chaplin.roots.Page {
    private int attempts;

    @Override
    public Node render(PageContext context) {
        return div(
                h1("Custom 404"),
                div("Missing path: ", context.path()).id("missing-path"),
                button("Recovery attempts: ", attempts).id("recovery-count")
                        .onClick(this, "retry")
        );
    }

    @ServerAction
    private void retry() {
        attempts++;
    }
}
