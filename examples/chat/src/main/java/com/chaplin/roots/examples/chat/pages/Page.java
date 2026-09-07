package com.chaplin.roots.examples.chat.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.examples.chat.components.ChatPanel;
import com.chaplin.roots.examples.chat.service.ChatRoom;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.aside;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.h2;
import static com.chaplin.roots.html.Html.main;
import static com.chaplin.roots.html.Html.p;
import static com.chaplin.roots.html.Html.section;
import static com.chaplin.roots.html.Html.span;
import static com.chaplin.roots.html.Html.strong;

@PageMetadata(
        title = "Roots Relay · Live Java chat",
        description = "A multi-view chat room built with Roots components and server-sent patches.",
        stylesheets = "/chat.css"
)
public final class Page implements com.chaplin.roots.Page {
    private final ChatPanel chat = new ChatPanel(ChatRoom.general());

    @Override
    public Node render(PageContext context) {
        return main(
                aside(
                        section(
                                span("ACTIVE ROOM").className("rail-label"),
                                h1("# general"),
                                p("A shared room held by one JVM. Every message is a Java state change.")
                        ).className("room-heading"),
                        div(
                                div(span("#"), strong("general")).className("room-row active"),
                                div(span("01"), span("room in this demo")).className("room-caption")
                        ).className("room-list"),
                        section(
                                span("SIGNAL PATH").className("rail-label"),
                                signal("ACTION", "@ServerAction"),
                                signal("STATE", "ChatRoom"),
                                signal("PUSH", "Server-Sent Events"),
                                signal("PATCH", "Keyed HTML")
                        ).className("signal-card"),
                        section(
                                strong("Try the live path"),
                                p("Open this page in another tab, choose a different name, and send a message.")
                        ).className("test-card")
                ).className("room-rail").attr("tabindex", 0),
                div(
                        headerCopy(),
                        chat
                ).className("conversation")
        ).className("relay-workspace");
    }

    private static Node headerCopy() {
        return section(
                div(
                        span("ROOM / GENERAL").className("conversation-code"),
                        h2("Coordinate without leaving Java.")
                ),
                p("A message crosses the network, mutates shared server state, and appears in every connected view—without an application websocket handler or client store.")
        ).className("conversation-intro");
    }

    private static Node signal(String label, String value) {
        return div(span(label), strong(value)).className("signal-row");
    }
}
