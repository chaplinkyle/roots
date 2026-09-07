package com.acme.pages.audit;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.Route;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.p;
import static com.chaplin.roots.html.Html.section;
import static com.chaplin.roots.html.Html.span;
import static com.chaplin.roots.html.Html.strong;

@Route("/activity")
@PageMetadata(
        title = "Activity · Roots Control",
        description = "Recent JVM application events.",
        stylesheets = "/app.css"
)
public final class Page implements com.chaplin.roots.Page {
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
