package com.chaplin.roots.errorpageapp.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.main;

@PageMetadata(title = "Custom application error", description = "The request could not be completed")
public final class ErrorPage implements com.chaplin.roots.Page {
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
