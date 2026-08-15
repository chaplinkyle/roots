package com.acme.pages;

import com.acme.components.ApprovalCounter;
import com.acme.components.Metric;
import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.html.Node;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static dev.roots.html.Html.div;
import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.h2;
import static dev.roots.html.Html.p;
import static dev.roots.html.Html.section;
import static dev.roots.html.Html.span;

@PageMetadata(
        title = "Operations · Roots Control",
        description = "A live enterprise operations workspace rendered entirely from Java.",
        stylesheets = "/app.css"
)
public final class Page implements dev.roots.Page {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss 'CT'")
            .withZone(ZoneId.of("America/Chicago"));

    private final ApprovalCounter approvals = new ApprovalCounter();
    private boolean visited;

    @Override
    public Node render(PageContext context) {
        if (!visited) {
            var visits = context.session().get("visits", Integer.class).orElse(0) + 1;
            context.session().put("visits", visits);
            visited = true;
        }
        var visits = context.session().get("visits", Integer.class).orElse(1);

        return div(
                section(
                        div(
                                span("SATURDAY · AUG 15").className("eyebrow"),
                                h1("The JVM is the full stack.")
                        ),
                        div(
                                span("LAST RENDER").className("eyebrow"),
                                p(TIME.format(Instant.now())).className("render-time"),
                                p("session visit " + visits).className("session-note")
                        ).className("render-stamp")
                ).className("page-heading split"),
                section(
                        new Metric("Requests today", "84,291", "+12.4%", Metric.Tone.BLUE),
                        new Metric("p95 render", "38 ms", "-7 ms", Metric.Tone.GREEN),
                        new Metric("Open reviews", "6", "needs action", Metric.Tone.AMBER)
                ).className("metrics-grid"),
                section(
                        div(
                                span("SERVER → BROWSER").className("eyebrow"),
                                h2("One action. One Java rerender."),
                                p("Clicking the control invokes an annotated method on this live component. Roots rerenders its Java tree and patches the page—no application JavaScript, JSON API, or client state store.")
                        ).className("explanation"),
                        approvals
                ).className("live-demo"),
                section(
                        span("REQUEST LIFECYCLE").className("eyebrow"),
                        div(
                                stage("01", "HTTP", "JDK server accepts the request"),
                                stage("02", "ROUTE", "Page.java is found by convention"),
                                stage("03", "RENDER", "Components produce safe HTML"),
                                stage("04", "PATCH", "The live root updates in place")
                        ).className("runtime-rail")
                ).className("lifecycle")
        ).className("page overview-page");
    }

    private static Node stage(String number, String name, String detail) {
        return div(
                span(number).className("stage-number"),
                div(span(name).className("stage-name"), p(detail))
        ).className("stage");
    }
}
