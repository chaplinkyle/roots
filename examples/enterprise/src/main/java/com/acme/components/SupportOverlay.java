package com.acme.components;

import dev.roots.ActionEvent;
import dev.roots.Component;
import dev.roots.PageContext;
import dev.roots.Ref;
import dev.roots.annotation.ServerAction;
import dev.roots.annotation.ViewComponent;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.dialog;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.fragment;
import static dev.roots.html.Html.h2;
import static dev.roots.html.Html.p;
import static dev.roots.html.Html.portal;

/** A retained Java component rendered through a body-level portal. */
@ViewComponent("support-overlay")
public final class SupportOverlay implements Component {
    private final Ref closeButton = Ref.create();
    private boolean open;

    @Override
    public Node render(PageContext context) {
        return fragment(
                button("Architecture notes")
                        .type("button")
                        .className("button quiet support-launch")
                        .onClick(this, "open"),
                open ? portal("support-overlay",
                        div(
                                dialog(
                                        div(
                                                p("ROOTS RUNTIME").className("eyebrow signal"),
                                                h2("The server still owns this overlay."),
                                                p("Its component state, action bindings, lifecycle, and focus effect are Java. The Roots driver only mounts the rendered node at document level."),
                                                button("Close notes")
                                                        .type("button")
                                                        .className("button primary")
                                                        .ref(closeButton)
                                                        .onClick(this, "close")
                                        ).className("support-dialog-content")
                                ).attr("open", true).aria("modal", "true").className("support-dialog")
                        ).className("support-backdrop")
                ) : null
        );
    }

    @ServerAction
    private void open(ActionEvent event) {
        open = true;
        event.focus(closeButton);
    }

    @ServerAction
    private void close() {
        open = false;
    }
}
