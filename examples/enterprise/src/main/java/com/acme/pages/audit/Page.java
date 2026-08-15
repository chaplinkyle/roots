package com.acme.pages.audit;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.annotation.Route;
import dev.roots.html.Node;

import static dev.roots.html.Html.div;
import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.p;
import static dev.roots.html.Html.section;
import static dev.roots.html.Html.span;
import static dev.roots.html.Html.strong;

@Route("/activity")
@PageMetadata(
        title = "Activity · Roots Control",
        description = "Recent JVM application events.",
        stylesheets = "/app.css"
)
public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return div(
                section(span("ANNOTATION ROUTE · @Route(\"/activity\")").className("eyebrow"), h1("Activity"),
                        p("Convention points this class at /audit; the annotation deliberately overrides it."))
                        .className("page-heading"),
                section(
                        event("14:32:08", "Access review approved", "actor kyle@acme.test"),
                        event("14:28:41", "Customer filter changed", "live component event"),
                        event("14:25:16", "Health route checked", "200 in 2 ms")
                ).className("activity-list")
        ).className("page activity-page");
    }

    private static Node event(String time, String title, String detail) {
        return div(span(time).className("event-time"), div(strong(title), p(detail))).className("activity-event");
    }
}
