package com.chaplin.roots.examples.chat.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.header;
import static com.chaplin.roots.html.Html.small;
import static com.chaplin.roots.html.Html.span;
import static com.chaplin.roots.html.Html.strong;

public final class Layout implements com.chaplin.roots.Layout {
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
