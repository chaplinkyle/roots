package com.acme.pages;

import com.chaplin.roots.HeadMetadata;
import com.chaplin.roots.OpenGraphMetadata;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Element;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.a;
import static com.chaplin.roots.html.Html.aside;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.footer;
import static com.chaplin.roots.html.Html.header;
import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.main;
import static com.chaplin.roots.html.Html.nav;
import static com.chaplin.roots.html.Html.small;
import static com.chaplin.roots.html.Html.span;
import static com.chaplin.roots.html.Html.strong;

public final class Layout implements com.chaplin.roots.Layout {
    @Override
    public HeadMetadata headMetadata(PageContext context) {
        return new HeadMetadata()
                .withRobots("index, follow")
                .withThemeColor("#10241f")
                .withOpenGraph(new OpenGraphMetadata().withSiteName("Roots Control"));
    }

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
                                        navItem(context, "/activity", "Activity", "03"),
                                        navItem(context, "/latency", "Latency", "04")
                                ).aria("label", "Primary"),
                                div(
                                        small("REQUEST PATH"),
                                        strong(context.path()),
                                        span("rendered on virtual thread")
                                ).className("request-path")
                        ).className("sidebar"),
                        main(children).className("content")
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
                .viewTransition()
                .aria("current", active ? "page" : null);
    }
}
