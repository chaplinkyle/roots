package dev.roots.html;

import dev.roots.PageContext;
import dev.roots.OptimisticEffect;
import dev.roots.Ref;
import dev.roots.Session;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.roots.html.Html.a;
import static dev.roots.html.Html.article;
import static dev.roots.html.Html.aside;
import static dev.roots.html.Html.br;
import static dev.roots.html.Html.button;
import static dev.roots.html.Html.code;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.footer;
import static dev.roots.html.Html.form;
import static dev.roots.html.Html.fragment;
import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.h2;
import static dev.roots.html.Html.h3;
import static dev.roots.html.Html.header;
import static dev.roots.html.Html.hr;
import static dev.roots.html.Html.input;
import static dev.roots.html.Html.label;
import static dev.roots.html.Html.li;
import static dev.roots.html.Html.link;
import static dev.roots.html.Html.main;
import static dev.roots.html.Html.nav;
import static dev.roots.html.Html.ol;
import static dev.roots.html.Html.option;
import static dev.roots.html.Html.p;
import static dev.roots.html.Html.pre;
import static dev.roots.html.Html.section;
import static dev.roots.html.Html.select;
import static dev.roots.html.Html.small;
import static dev.roots.html.Html.span;
import static dev.roots.html.Html.strong;
import static dev.roots.html.Html.table;
import static dev.roots.html.Html.tag;
import static dev.roots.html.Html.tbody;
import static dev.roots.html.Html.td;
import static dev.roots.html.Html.text;
import static dev.roots.html.Html.textarea;
import static dev.roots.html.Html.th;
import static dev.roots.html.Html.thead;
import static dev.roots.html.Html.time;
import static dev.roots.html.Html.tr;
import static dev.roots.html.Html.ul;
import static dev.roots.html.Html.unsafeHtml;
import static dev.roots.html.Html.validationMessage;
import static dev.roots.html.Html.validationSummary;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HtmlDslTest {
    private final PageContext context = new PageContext("/", Map.of(), Map.of(), new Session("html-dsl"));

    @Test
    void rendersTheCompleteConvenienceDslAndFlattensChildren() {
        var tree = fragment(
                main(header(nav(link("/", "Home"))), section(article(h1("One"), h2("Two"), h3("Three")))),
                aside(p("Paragraph", br(), strong("strong"), small("small"), span("span"))),
                ul(List.of(li("A"), li("B"))),
                ol(li("One")),
                form(label("Name"), input().name("name").type("text").value("Ada").placeholder("Name"), textarea("Bio"),
                        select(option("One"))),
                table(thead(tr(th("Header"))), tbody(tr(td("Cell")))),
                pre(code("code")),
                time("now"),
                a("plain").href("/plain"),
                button("button"),
                div(text(null), unsafeHtml("<em>trusted</em>")),
                validationMessage("email"),
                validationSummary(),
                hr(),
                footer("Footer"),
                null
        );

        var html = HtmlRenderer.render(tree, context).html();

        assertTrue(html.contains("<main>"), html);
        assertTrue(html.contains("<a href=\"/\" data-roots-link>Home</a>"), html);
        assertTrue(html.contains("<input name=\"name\" type=\"text\" value=\"Ada\" placeholder=\"Name\">"), html);
        assertTrue(html.contains("<em>trusted</em>"), html);
        assertTrue(html.contains("<li>A</li><li>B</li>"), html);
        assertTrue(html.contains("data-roots-validation-for=\"email\" hidden aria-live=\"polite\""), html);
        assertTrue(html.contains("data-roots-validation-summary hidden role=\"alert\""), html);
        assertFalse(html.contains("</input>"), html);
    }

    @Test
    void fluentAttributesRefsAndEveryEventBindingRenderSafely() {
        var ref = Ref.create();
        var element = button("Run")
                .id("run")
                .className("primary")
                .aria("label", "Run action")
                .aria("hidden", true)
                .aria("expanded", false)
                .data("kind", "test")
                .key(42)
                .ref(ref)
                .pendingScope()
                .attr("disabled", true)
                .attr("removed", "yes")
                .attr("removed", false)
                .onClick("click-action", ignored -> { })
                .onSubmit("submit-action", ignored -> { })
                .onChange("change-action", ignored -> { })
                .onInput("input-action", ignored -> { })
                .onKeyDown("keydown-action", ignored -> { })
                .onKeyUp("keyup-action", ignored -> { })
                .onFocus("focus-action", ignored -> { })
                .onBlur("blur-action", ignored -> { })
                .onDoubleClick("double-action", ignored -> { })
                .onPointerDown("pointerdown-action", ignored -> { })
                .onPointerUp("pointerup-action", ignored -> { });

        var rendered = HtmlRenderer.render(element, context);

        assertTrue(rendered.html().contains("disabled"));
        assertTrue(rendered.html().contains("aria-hidden=\"true\""), rendered.html());
        assertTrue(rendered.html().contains("aria-expanded=\"false\""), rendered.html());
        assertFalse(rendered.html().contains("removed"));
        assertTrue(rendered.html().contains("data-roots-ref=\"" + ref.id() + "\""));
        assertTrue(rendered.html().contains("data-roots-pending-scope"));
        assertTrue(rendered.html().contains("data-roots-on-input=\"input-action\""));
        assertTrue(rendered.actions().keySet().containsAll(List.of(
                "click-action", "submit-action", "change-action", "input-action",
                "keydown-action", "keyup-action", "focus-action", "blur-action",
                "double-action", "pointerdown-action", "pointerup-action"
        )));
    }

    @Test
    void rejectsInvalidMarkupAndConflictingActions() {
        assertThrows(IllegalArgumentException.class, () -> tag("Bad"));
        assertThrows(IllegalArgumentException.class, () -> validationMessage(" "));
        assertThrows(IllegalArgumentException.class, () -> validationMessage("bad\nfield"));
        assertThrows(IllegalArgumentException.class, () -> div().attr("bad attribute", "x"));
        assertThrows(IllegalArgumentException.class, () -> div().attr("onClick", "javascript()"));
        assertThrows(IllegalArgumentException.class, () -> button().on("bad-event", "valid", ignored -> { }));
        assertThrows(IllegalArgumentException.class, () -> button().on("wheel", "valid", ignored -> { }));
        assertThrows(IllegalArgumentException.class, () -> button().on("click", "bad action", ignored -> { }));
        assertThrows(IllegalStateException.class, () -> button().viewTransition());
        assertTrue(link("/next", "Next").viewTransition().attributes()
                .containsKey("data-roots-view-transition"));
        assertThrows(IllegalStateException.class, () -> HtmlRenderer.render(
                fragment(
                        button("One").onClick("duplicate", ignored -> { }),
                        button("Two").onClick("duplicate", ignored -> { })
                ),
                context
        ));

        var ref = Ref.create();
        var element = button().onClick("bounded", ignored -> { });
        for (var index = 0; index < 32; index++) {
            element.optimistic(OptimisticEffect.disable(ref));
        }
        assertThrows(IllegalArgumentException.class,
                () -> element.optimistic(OptimisticEffect.disable(ref)));
        assertThrows(NullPointerException.class, () -> button().optimistic((OptimisticEffect) null));
    }
}
