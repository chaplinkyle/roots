package dev.roots.errorpageapp.pages;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.main;

@PageMetadata(title = "Custom application error", description = "The request could not be completed")
public final class ErrorPage implements dev.roots.Page {
    public static volatile boolean FAIL_RENDER;
    private int retries;

    @Override
    public Node render(PageContext context) {
        if (FAIL_RENDER) {
            throw new IllegalStateException("expected error-page failure");
        }
        return main(
                h1("Something went wrong"),
                div("Support trace: ", context.traceContext().traceId()).id("support-trace"),
                button("Retry attempts: ", retries).id("error-retry").onClick(this, "retry")
        );
    }

    @ServerAction
    private void retry() {
        retries++;
    }
}
