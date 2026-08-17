package dev.roots.notfoundapp.pages;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.h1;

@PageMetadata(title = "Custom not found", description = "The requested page does not exist")
public final class NotFound implements dev.roots.Page {
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
