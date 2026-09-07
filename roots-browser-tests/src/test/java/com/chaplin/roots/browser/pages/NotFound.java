package com.chaplin.roots.browser.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.link;

@PageMetadata(title = "Roots not found fixture", description = "Browser-tested custom missing page")
public final class NotFound implements com.chaplin.roots.Page {
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
