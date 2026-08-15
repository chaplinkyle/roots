package com.acme.components;

import dev.roots.Component;
import dev.roots.PageContext;
import dev.roots.annotation.ViewComponent;
import dev.roots.html.Node;

import static dev.roots.html.Html.div;
import static dev.roots.html.Html.small;
import static dev.roots.html.Html.span;

@ViewComponent("metric")
public record Metric(String label, String value, String change, Tone tone) implements Component {
    public enum Tone { BLUE, GREEN, AMBER }

    @Override
    public Node render(PageContext context) {
        return div(
                div(label, span(change).className("metric-change " + tone.name().toLowerCase()))
                        .className("metric-label"),
                div(value).className("metric-value"),
                small("live from Java state").className("metric-footnote")
        ).className("metric");
    }
}
