package com.acme.pages.latency;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;
import static com.chaplin.roots.html.Html.*;

@PageMetadata(title = "Latency · Roots Control", stylesheets = {"/app.css", "/widgets/latency.css"})
public final class Page implements com.chaplin.roots.Page {
    private int refresh;

    @Override public Node render(PageContext context) {
        var values = java.util.stream.IntStream.range(0, 24)
                .map(i -> 35 + Math.floorMod(i * 17 + refresh * 11, 90))
                .mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(","));
        return section(
                div(h1("Request latency"), p("Explore the last 24 samples. Refreshing samples preserves your selected range.")),
                div(p("Latency samples in milliseconds: ", values)).id("latency-chart")
                        .widget("latency-chart", "/widgets/latency.js").data("widget-values", values),
                button("Refresh samples").id("refresh-samples").className("button primary").onClick(this, "refresh"),
                p("Illustrative data · sample set ", span(refresh).id("sample-set")).className("latency-caption")
        ).className("page latency-page");
    }

    @ServerAction private void refresh() { refresh++; }
}
