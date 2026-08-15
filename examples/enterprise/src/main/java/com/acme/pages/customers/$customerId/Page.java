package com.acme.pages.customers.$customerId;

import dev.roots.Metadata;
import dev.roots.PageContext;
import dev.roots.html.Node;

import static dev.roots.html.Html.div;
import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.link;
import static dev.roots.html.Html.p;
import static dev.roots.html.Html.section;
import static dev.roots.html.Html.span;
import static dev.roots.html.Html.strong;

public final class Page implements dev.roots.Page {
    @Override
    public Metadata metadata(PageContext context) {
        return Metadata.of(
                "Customer " + context.parameter("customerId") + " · Roots Control",
                "A convention-routed customer record.",
                "/app.css"
        );
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
