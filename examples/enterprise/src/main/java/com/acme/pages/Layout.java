package com.acme.pages;

import dev.roots.PageContext;
import dev.roots.html.Element;
import dev.roots.html.Node;

import static dev.roots.html.Html.a;
import static dev.roots.html.Html.aside;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.footer;
import static dev.roots.html.Html.header;
import static dev.roots.html.Html.link;
import static dev.roots.html.Html.nav;
import static dev.roots.html.Html.small;
import static dev.roots.html.Html.span;
import static dev.roots.html.Html.strong;

public final class Layout implements dev.roots.Layout {
    @Override
    public Node render(PageContext context, Node children) {
        return div(
                header(
                        div(
                                span("R/").className("brand-mark"),
                                div(strong("Roots Control"), small("JVM operations workspace"))
                        ).className("brand"),
                        div(
                                span().className("health-dot").aria("hidden", true),
                                span("JVM healthy"),
                                a("API").href("/api/health").className("api-link")
                        ).className("runtime-health")
                ).className("topbar"),
                div(
                        aside(
                                nav(
                                        navItem(context, "/", "Overview", "01"),
                                        navItem(context, "/customers", "Customers", "02"),
                                        navItem(context, "/activity", "Activity", "03")
                                ).aria("label", "Primary"),
                                div(
                                        small("REQUEST PATH"),
                                        strong(context.path()),
                                        span("rendered on virtual thread")
                                ).className("request-path")
                        ).className("sidebar"),
                        div(children).className("content")
                ).className("workspace"),
                footer("Roots 0.1 · Java in. HTML out.").className("app-footer")
        ).className("app-frame");
    }

    private static Element navItem(PageContext context, String href, String label, String number) {
        var active = href.equals("/") ? context.path().equals("/") : context.path().startsWith(href);
        return link(href,
                span(number).className("nav-number"),
                span(label)
        ).className("nav-item" + (active ? " active" : ""))
                .aria("current", active ? "page" : null);
    }
}
