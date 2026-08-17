package dev.roots.browser.pages;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.link;

@PageMetadata(title = "Roots not found fixture", description = "Browser-tested custom missing page")
public final class NotFound implements dev.roots.Page {
    private int recoveryCount;

    @Override
    public Node render(PageContext context) {
        return div(
                h1("That page wandered off").id("not-found-heading"),
                div(context.path()).id("not-found-path"),
                button("Recovery ", recoveryCount).id("not-found-action")
                        .onClick(this, "recover"),
                link("/", "Return home").id("not-found-home")
        );
    }

    @ServerAction
    private void recover() {
        recoveryCount++;
    }
}
