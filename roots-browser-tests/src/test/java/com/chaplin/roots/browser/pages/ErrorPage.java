package com.chaplin.roots.browser.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.main;

@PageMetadata(title = "Roots error fixture", description = "Browser-tested application error page")
public final class ErrorPage implements com.chaplin.roots.Page {
    private int reports;

    @Override
    public Node render(PageContext context) {
        return main(
                h1("We could not load that page").id("error-page-heading"),
                div("Support reference: ", context.traceContext().traceId()).id("error-page-trace"),
                button("Reports ", reports).id("error-page-action").onClick(this, "report"),
                link("/", "Return home").id("error-page-home")
        );
    }

    @ServerAction
    private void report() {
        reports++;
    }
}
