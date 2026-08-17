package dev.roots.browser.pages;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.link;
import static dev.roots.html.Html.main;

@PageMetadata(title = "Roots error fixture", description = "Browser-tested application error page")
public final class ErrorPage implements dev.roots.Page {
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
