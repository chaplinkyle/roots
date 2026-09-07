package com.chaplin.roots.examples.chat.components;

import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.Component;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.Ref;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.annotation.ViewComponent;
import com.chaplin.roots.examples.chat.service.ChatRoom;
import com.chaplin.roots.examples.chat.service.ChatRoom.ChatMessage;
import com.chaplin.roots.html.Node;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import static com.chaplin.roots.html.Html.article;
import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.form;
import static com.chaplin.roots.html.Html.header;
import static com.chaplin.roots.html.Html.input;
import static com.chaplin.roots.html.Html.label;
import static com.chaplin.roots.html.Html.p;
import static com.chaplin.roots.html.Html.section;
import static com.chaplin.roots.html.Html.span;
import static com.chaplin.roots.html.Html.strong;
import static com.chaplin.roots.html.Html.textarea;
import static com.chaplin.roots.html.Html.time;

@ViewComponent("chat-panel")
public final class ChatPanel implements Component {
    private static final String DISPLAY_NAME = "roots-relay-display-name";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm 'CT'", Locale.ROOT)
            .withZone(ZoneId.of("America/Chicago"));

    private final ChatRoom room;
    private final String subscriberId = UUID.randomUUID().toString();
    private final Ref messageInput = Ref.create();
    private final Ref transcriptEnd = Ref.create();
    private ChatRoom.Subscription subscription;
    private String displayName;

    public ChatPanel(ChatRoom room) {
        this.room = room;
    }

    @Override
    public Node render(PageContext context) {
        if (displayName == null) {
            displayName = context.session().get(DISPLAY_NAME, String.class)
                    .orElseGet(() -> "Guest " + context.session().id().substring(0, 4).toUpperCase(Locale.ROOT));
        }
        var messages = room.messages();

        return section(
                header(
                        div(
                                span("GENERAL CHANNEL").className("channel-kicker"),
                                strong("Live dispatch").className("channel-title")
                        ),
                        div(
                                span().className("live-pulse").aria("hidden", true),
                                span("LIVE"),
                                span(messages.size() + " messages").className("message-count")
                        ).className("channel-status")
                ).className("channel-header"),
                div(
                        messages.stream().map(this::message).toList(),
                        div().className("transcript-end").ref(transcriptEnd).aria("hidden", true)
                ).className("message-ledger")
                        .attr("role", "log")
                        .attr("tabindex", 0)
                        .aria("live", "polite")
                        .aria("relevant", "additions text")
                        .aria("label", "General channel messages"),
                form(
                        div(
                                label(
                                        span("Display name"),
                                        input()
                                                .name("author")
                                                .value(displayName)
                                                .attr("maxlength", 32)
                                                .attr("autocomplete", "nickname")
                                                .attr("required", true)
                                ),
                                label(
                                        span("Message"),
                                        textarea()
                                                .name("message")
                                                .placeholder("Write an update for the room")
                                                .attr("maxlength", 500)
                                                .attr("rows", 3)
                                                .attr("required", true)
                                                .ref(messageInput)
                                )
                        ).className("composer-fields"),
                        div(
                                p("Messages are rendered in Java and pushed to every open view."),
                                button("Send message").type("submit").className("send-button")
                        ).className("composer-actions")
                ).className("composer").onSubmit(this, "send")
        ).className("chat-panel");
    }

    @Override
    public void onMount(PageContext context) {
        subscription = room.subscribe(subscriberId, () -> context.update(() -> {
            // The shared room changed; rerender this live view and enqueue an SSE patch.
        }));
    }

    @Override
    public void onUnmount(PageContext context) {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }

    @ServerAction("send")
    private void send(ActionEvent event) {
        var posted = room.post(
                subscriberId,
                event.required("author"),
                event.required("message")
        );
        displayName = posted.author();
        event.session().put(DISPLAY_NAME, displayName);
        event.scrollIntoView(transcriptEnd);
        event.focus(messageInput);
    }

    private Node message(ChatMessage message) {
        return article(
                div(
                        span("%03d".formatted(message.id())).className("message-sequence"),
                        span().className("signal-node").aria("hidden", true)
                ).className("signal-index"),
                div(
                        header(
                                strong(message.author()),
                                time(CLOCK.format(message.sentAt()))
                                        .attr("datetime", message.sentAt().toString())
                        ).className("message-meta"),
                        p(message.body()).className("message-body")
                ).className("message-content")
        ).className("message-entry").key(message.id());
    }
}
