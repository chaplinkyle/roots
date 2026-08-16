package dev.roots.examples.chat.pages;

import dev.roots.PageContext;
import dev.roots.html.Node;

import static dev.roots.html.Html.div;
import static dev.roots.html.Html.header;
import static dev.roots.html.Html.small;
import static dev.roots.html.Html.span;
import static dev.roots.html.Html.strong;

public final class Layout implements dev.roots.Layout {
    @Override
    public Node render(PageContext context, Node children) {
        return div(
                header(
                        div(
                                span("R/").className("relay-mark"),
                                div(
                                        strong("ROOTS RELAY"),
                                        small("Java live-room protocol")
                                ).className("relay-wordmark")
                        ).className("relay-brand"),
                        div(
                                span("SSE").className("protocol-chip"),
                                span("JVM ONLINE").className("jvm-status")
                        ).className("protocol-status")
                ).className("relay-topbar"),
                children
        ).className("relay-frame");
    }
}
