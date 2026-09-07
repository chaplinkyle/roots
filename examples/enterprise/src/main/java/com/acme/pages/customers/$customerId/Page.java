package com.acme.pages.customers.$customerId;

import com.chaplin.roots.HeadMetadata;
import com.chaplin.roots.Metadata;
import com.chaplin.roots.OpenGraphMetadata;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.p;
import static com.chaplin.roots.html.Html.section;
import static com.chaplin.roots.html.Html.span;
import static com.chaplin.roots.html.Html.strong;

public final class Page implements com.chaplin.roots.Page {
    @Override
    public Metadata metadata(PageContext context) {
        return Metadata.of(
                "Customer " + context.parameter("customerId") + " · Roots Control",
                "A convention-routed customer record.",
                "/app.css"
        );
    }

    @Override
    public HeadMetadata headMetadata(PageContext context) {
        var id = context.parameter("customerId");
        return new HeadMetadata()
                .withCanonical(context.path())
                .withRobots("noindex, nofollow")
                .withThemeColor("#10241f")
                .withOpenGraph(new OpenGraphMetadata()
                        .withTitle("Customer " + id)
                        .withDescription("A convention-routed customer record.")
                        .withType("website")
                        .withUrl(context.path()));
    }

    @Override
    public Node render(PageContext context) {
        var id = context.parameter("customerId");
        return div(
                link("/customers", "← All customers").className("back-link"),
                section(
                        span("DYNAMIC ROUTE · $customerId").className("eyebrow"),
                        h1("Customer " + id),
                        p("This page was matched from the Java package "),
                        div(
                                detail("Route parameter", id),
                                detail("Render owner", "server JVM"),
                                detail("Client framework", "none")
                        ).className("detail-grid")
                ).className("record-card")
        ).className("page detail-page");
    }

    private static Node detail(String label, String value) {
        return div(span(label), strong(value)).className("detail");
    }
}
