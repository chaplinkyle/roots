package com.acme.components;

import com.chaplin.roots.Component;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.ViewComponent;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.small;
import static com.chaplin.roots.html.Html.span;

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
