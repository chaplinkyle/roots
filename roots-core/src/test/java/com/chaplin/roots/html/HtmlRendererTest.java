package com.chaplin.roots.html;

import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.Component;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.OptimisticEffect;
import com.chaplin.roots.Ref;
import com.chaplin.roots.RootsCache;
import com.chaplin.roots.Session;
import com.chaplin.roots.TraceContext;
import com.chaplin.roots.annotation.ServerAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.chaplin.roots.html.Html.boundary;
import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.dialog;
import static com.chaplin.roots.html.Html.portal;
import static com.chaplin.roots.html.Html.a;
import static com.chaplin.roots.html.Html.form;
import static com.chaplin.roots.html.Html.image;
import static com.chaplin.roots.html.Html.input;
import static com.chaplin.roots.html.Html.modal;
import static com.chaplin.roots.html.Html.p;
import static com.chaplin.roots.html.Html.tag;
import static com.chaplin.roots.html.Html.span;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HtmlRendererTest {
    private final PageContext context = new PageContext("/", Map.of(), Map.of(), new Session("test"));

    @Test
    void widgetsHaveEscapedMountedModulesAndStaticFallbackBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> HtmlRenderer.render(div().key("remote")
                .attr("DATA-ROOTS-WIDGET", "https://example.com/code.js"), context));
        assertThrows(IllegalStateException.class, () -> HtmlRenderer.render(div(span("Edit")
                .attr("CONTENTEDITABLE", true)).widget("editor", "/editor.js"), context));
        var mounted = new PageContext("/", Map.of(), Map.of(), new Session("widget"), RootsCache.disabled(),
                Optional.empty(), TraceContext.create(), "/company");
        var html = HtmlRenderer.render(div(p("Static <fallback>"))
                .widget("chart", "/widgets/chart.js?v=1&theme=dark").data("widget-title", "\"<&"), mounted).html();
        assertTrue(html.contains("data-roots-widget=\"/company/widgets/chart.js?v=1&amp;theme=dark\""));
        assertTrue(html.contains("data-widget-title=\"&quot;&lt;&amp;\""));
        assertTrue(html.contains("Static &lt;fallback&gt;"));
        for (var url : List.of("https://example.com/a.js", "//example.com/a.js", "javascript:alert(1)", "/a.js#fragment", "/\\example.com/a.js")) {
            assertThrows(IllegalArgumentException.class, () -> div().widget("chart", url));
        }
        assertThrows(IllegalArgumentException.class, () -> div().widget(" ", "/chart.js"));
        assertThrows(IllegalStateException.class, () -> HtmlRenderer.render(input().widget("chart", "/chart.js"), context));
        assertThrows(IllegalStateException.class, () -> HtmlRenderer.render(div(button("Action")).widget("chart", "/chart.js"), context));
        assertThrows(IllegalStateException.class, () -> HtmlRenderer.render(div((Component) ignored -> p("Live"))
                .widget("chart", "/chart.js"), context));
        assertThrows(IllegalStateException.class, () -> HtmlRenderer.render(div(div().widget("inner", "/chart.js"))
                .widget("outer", "/chart.js"), context));
    }

    @Test
    void escapesTextAndAttributesByDefault() {
        var rendered = HtmlRenderer.render(
                div("<script>alert('no')</script>").attr("title", "\"<&"),
                context
        );

        assertEquals("<div title=\"&quot;&lt;&amp;\">&lt;script&gt;alert('no')&lt;/script&gt;</div>", rendered.html());
    }

    @Test
    void prefixesRootRelativeUrlsAtAnExternalMountWithoutTouchingOtherUrls() {
        var mounted = new PageContext(
                "/",
                Map.of(),
                Map.of(),
                new Session("mounted"),
                RootsCache.disabled(),
                Optional.empty(),
                TraceContext.create(),
                "/company/roots"
        );

        var html = HtmlRenderer.render(div(
                a("Home").href("/"),
                tag("img").attr("src", "/images/logo.svg"),
                form().attr("action", "/submit"),
                a("Mounted").href("/company/roots/already"),
                a("External").href("https://example.com"),
                a("Protocol relative").href("//cdn.example.com/file")
        ), mounted).html();

        assertTrue(html.contains("href=\"/company/roots/\""), html);
        assertTrue(html.contains("src=\"/company/roots/images/logo.svg\""), html);
        assertTrue(html.contains("action=\"/company/roots/submit\""), html);
        assertTrue(html.contains("href=\"/company/roots/already\""), html);
        assertTrue(html.contains("href=\"https://example.com\""), html);
        assertTrue(html.contains("href=\"//cdn.example.com/file\""), html);
    }

    @Test
    void rendersResponsiveOptimizedImagesWithMountedUrlsAndPriorityHints() {
        var mounted = new PageContext(
                "/", Map.of(), Map.of(), new Session("images"), RootsCache.disabled(),
                Optional.empty(), TraceContext.create(), "/company/roots"
        );

        var html = HtmlRenderer.render(image("/images/hero.png", "A forest", 1200, 800)
                .widths(320, 640, 960)
                .quality(75)
                .sizes("(max-width: 700px) 100vw, 1200px")
                .priority()
                .className("hero")
                .attr("aria-describedby", "hero-caption"), mounted).html();

        assertTrue(html.startsWith("<img src=\"/company/roots/_roots/image?src=%2Fimages%2Fhero.png&amp;w=1200&amp;q=75\""), html);
        assertTrue(html.contains("srcset=\"/company/roots/_roots/image?src=%2Fimages%2Fhero.png&amp;w=320&amp;q=75 320w, "), html);
        assertTrue(html.contains("loading=\"eager\""), html);
        assertTrue(html.contains("fetchpriority=\"high\""), html);
        assertTrue(html.contains("sizes=\"(max-width: 700px) 100vw, 1200px\""), html);
        assertTrue(html.contains("class=\"hero\" aria-describedby=\"hero-caption\""), html);
    }

    @Test
    void optimizedImagesRejectUnsafeOrUnboundedConfiguration() {
        assertThrows(NullPointerException.class, () -> image(null, "alt", 1, 1));
        assertThrows(NullPointerException.class, () -> image("/a.png", null, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> image("https://example.com/a.png", "alt", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> image("/../a.png", "alt", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> image("/a.svg", "alt", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> image("/a.png", "alt", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> image("/a.png", "alt", 1, 1).widths(0));
        assertThrows(IllegalArgumentException.class, () -> image("/a.png", "alt", 1, 1)
                .widths(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13));
        assertThrows(IllegalArgumentException.class, () -> image("/a.png", "alt", 1, 1).quality(101));
        assertThrows(IllegalArgumentException.class, () -> image("/a.png", "alt", 1, 1).sizes(" "));
        assertThrows(IllegalArgumentException.class, () -> image("/a.png", "alt", 1, 1).attr("src", "/b.png"));
        assertThrows(IllegalArgumentException.class, () -> image("/a.png", "alt", 1, 1).attr("onclick", "bad"));
    }

    @Test
    void composesComponentsAndEmitsStableKeys() {
        Component badge = page -> span("ready").className("badge").key("health");

        var rendered = HtmlRenderer.render(div("Status: ", badge), context);

        assertEquals("<div>Status: <span data-roots-component=\"c0\" class=\"badge\" data-roots-key=\"health\">ready</span></div>", rendered.html());
        assertEquals(List.of(badge), rendered.components());
        assertEquals(Map.of(
                "c0",
                "<span data-roots-component=\"c0\" class=\"badge\" data-roots-key=\"health\">ready</span>"
        ), rendered.componentHtml());
    }

    @Test
    void recordsNestedElementComponentsButLeavesFragmentComponentsUnscoped() {
        Component inner = page -> span("inner");
        Component outer = page -> div(inner);
        Component fragment = page -> Html.fragment("before", span("after"));

        var rendered = HtmlRenderer.render(div(outer, fragment), context);

        assertEquals(Set.of("c0", "c1"), rendered.componentHtml().keySet());
        assertEquals(
                "<span data-roots-component=\"c1\">inner</span>",
                rendered.componentHtml().get("c1")
        );
        assertTrue(rendered.componentHtml().get("c0").contains("data-roots-component=\"c1\""));
        assertTrue(rendered.html().endsWith("before<span>after</span></div>"), rendered.html());
    }

    @Test
    void errorBoundaryRollsBackComponentIdsAndRegions() {
        Component recovered = page -> span("recovered");
        Component partialThenBroken = page -> div(
                (Component) nested -> span("discarded"),
                boundary((Component) nested -> {
                    throw new IllegalStateException("broken");
                }, (error, nested) -> {
                    throw new IllegalStateException("fallback also broken");
                })
        );

        var rendered = HtmlRenderer.render(
                boundary(partialThenBroken, (error, page) -> recovered),
                context
        );

        assertEquals("<span data-roots-component=\"c0\">recovered</span>", rendered.html());
        assertEquals(Map.of("c0", rendered.html()), rendered.componentHtml());
    }

    @Test
    void reservesRendererOwnedComponentIdentity() {
        assertThrows(IllegalArgumentException.class, () -> div().data("roots-component", "spoofed"));
        assertThrows(IllegalArgumentException.class, () -> div().attr("DATA-ROOTS-COMPONENT", "spoofed"));
    }

    @Test
    void bindsAnnotatedServerActions() throws Exception {
        var counter = new Counter();
        var rendered = HtmlRenderer.render(counter, context);
        var action = rendered.actions().values().stream().findFirst().orElseThrow();

        action.handle(new ActionEvent("click", Map.of(), context.session()));

        assertEquals(1, counter.count);
        assertTrue(rendered.html().contains("data-roots-on-click=\"Counter-"));
    }

    @Test
    void bindsOneAnnotatedActionToMultipleBrowserEvents() {
        var counter = new Counter();
        var rendered = HtmlRenderer.render(
                button("Run")
                        .onClick(counter, "increment")
                        .onKeyDown(counter, "increment"),
                context
        );

        assertTrue(rendered.html().contains("data-roots-on-click="));
        assertTrue(rendered.html().contains("data-roots-on-keydown="));
        assertEquals(1, rendered.actions().size());
    }

    @Test
    void rollsBackPartialOutputInsideAnErrorBoundary() {
        Component broken = page -> {
            throw new IllegalStateException("database unavailable");
        };

        var rendered = HtmlRenderer.render(
                boundary(div("before", broken), (error, page) -> div("Recovered: ", error.getMessage())),
                context
        );

        assertEquals("<div>Recovered: database unavailable</div>", rendered.html());
    }

    @Test
    void rejectsInlineJavascriptHandlers() {
        assertThrows(IllegalArgumentException.class, () -> button("bad").attr("onclick", "steal()"));
    }

    @Test
    void emitsStableRefsAndTypedClientEffects() {
        var ref = Ref.create();
        var rendered = HtmlRenderer.render(button("Copy").ref(ref), context);
        var event = new ActionEvent("click", Map.of(), context.session());
        event.focus(ref);
        event.copyToClipboard("customer-42");

        assertTrue(rendered.html().contains("data-roots-ref=\"" + ref.id() + "\""));
        assertEquals(2, event.effects().size());
        assertEquals("customer-42", event.effects().getLast().value());
    }

    @Test
    void rendersTypedOptimisticEffectsAsEscapedJson() {
        var ref = Ref.create();
        var rendered = HtmlRenderer.render(
                button("Save").ref(ref)
                        .optimistic(
                                OptimisticEffect.text(ref, "Saving \"now\"\n<safe>"),
                                OptimisticEffect.disable(ref)
                        )
                        .onClick("save", ignored -> { }),
                context
        );

        assertTrue(rendered.html().contains("data-roots-optimistic=\""));
        assertTrue(rendered.html().contains("&quot;type&quot;:&quot;TEXT&quot;"));
        assertTrue(rendered.html().contains("Saving \\&quot;now\\&quot;\\n&lt;safe&gt;"));
        assertEquals(List.of("save"), rendered.actions().keySet().stream().toList());
    }

    @Test
    void rejectsOptimisticEffectsWithoutAnAction() {
        var ref = Ref.create();

        assertThrows(IllegalStateException.class, () -> HtmlRenderer.render(
                button("Save").ref(ref).optimistic(OptimisticEffect.disable(ref)),
                context
        ));
    }

    @Test
    void rendersPortalChildrenAndRegistersTheirComponentsAndActions() throws Exception {
        var counter = new Counter();
        var rendered = HtmlRenderer.render(
                div("page", portal("overlay", dialog(counter).attr("open", true))),
                context
        );

        assertTrue(rendered.html().startsWith(
                "<div>page<template data-roots-portal=\"overlay\"><dialog open><button data-roots-component=\"c0\" data-roots-on-click=\"Counter-"));
        assertTrue(rendered.html().endsWith(":increment\">0</button></dialog></template></div>"));
        assertEquals(List.of(counter), rendered.components());
        assertEquals(1, rendered.actions().size());
        rendered.actions().values().stream().findFirst().orElseThrow()
                .handle(new ActionEvent("click", Map.of(), context.session()));
        assertEquals(1, counter.count);
    }

    @Test
    void rendersAccessibleModalPortalAndRegistersOneDismissAction() throws Exception {
        var counter = new Counter();
        var inputRef = Ref.create();
        var dialogRef = Ref.create();

        var rendered = HtmlRenderer.render(modal(
                "settings", "Account settings", counter, "increment",
                p("Update preferences").id("settings-description"),
                input().ref(inputRef),
                button("Close").onClick(counter, "increment")
        ).initialFocus(inputRef)
                .ref(dialogRef)
                .dismissOnBackdrop(false)
                .id("settings-dialog")
                .className("settings")
                .describedBy("settings-description"), context);

        assertTrue(rendered.html().startsWith("<template data-roots-portal=\"settings\"><dialog"), rendered.html());
        assertTrue(rendered.html().contains("open tabindex=\"-1\" aria-modal=\"true\""), rendered.html());
        assertTrue(rendered.html().contains("aria-labelledby=\"roots-modal-title-settings\""), rendered.html());
        assertTrue(rendered.html().contains("data-roots-modal=\"settings\""), rendered.html());
        assertTrue(rendered.html().contains("data-roots-modal-backdrop=\"false\""), rendered.html());
        assertTrue(rendered.html().contains("data-roots-modal-initial-ref=\"" + inputRef.id() + "\""), rendered.html());
        assertTrue(rendered.html().contains("data-roots-ref=\"" + dialogRef.id() + "\""), rendered.html());
        assertTrue(rendered.html().contains("<h2 id=\"roots-modal-title-settings\">Account settings</h2>"), rendered.html());
        assertEquals(1, rendered.actions().size());

        rendered.actions().values().stream().findFirst().orElseThrow()
                .handle(new ActionEvent("keydown", Map.of(), context.session()));
        assertEquals(1, counter.count);

        var defaultRendered = HtmlRenderer.render(modal(
                "confirm", "Confirm change", "dismiss", ignored -> { }, button("Okay")
        ).className("temporary")
                .className(null)
                .attr("data-purpose", "confirmation")
                .attr("data-removed", "value")
                .attr("data-removed", null), context);
        assertTrue(defaultRendered.html().contains("data-roots-modal-backdrop=\"true\""), defaultRendered.html());
        assertTrue(defaultRendered.html().contains("data-purpose=\"confirmation\""), defaultRendered.html());
        assertFalse(defaultRendered.html().contains("temporary"), defaultRendered.html());
        assertFalse(defaultRendered.html().contains("data-removed"), defaultRendered.html());
    }

    @Test
    void rejectsUnsafeModalConfigurationAndControlledAttributes() {
        assertThrows(NullPointerException.class, () -> modal(null, "Title", "close", ignored -> { }));
        assertThrows(IllegalArgumentException.class, () -> modal("", "Title", "close", ignored -> { }));
        assertThrows(IllegalArgumentException.class, () -> modal("bad modal", "Title", "close", ignored -> { }));
        assertThrows(NullPointerException.class, () -> modal("good", null, "close", ignored -> { }));
        assertThrows(IllegalArgumentException.class, () -> modal("good", " ", "close", ignored -> { }));
        assertThrows(IllegalArgumentException.class, () -> modal("good", "Bad\ntitle", "close", ignored -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> modal("good", "x".repeat(257), "close", ignored -> { }));
        assertThrows(NullPointerException.class,
                () -> modal("good", "Title", (String) null, ignored -> { }));
        assertThrows(IllegalArgumentException.class, () -> modal("good", "Title", "bad action", ignored -> { }));
        assertThrows(NullPointerException.class,
                () -> modal("good", "Title", "close", (com.chaplin.roots.Action) null));
        assertThrows(NullPointerException.class,
                () -> modal("good", "Title", "close", ignored -> { }).initialFocus(null));
        assertThrows(NullPointerException.class,
                () -> modal("good", "Title", "close", ignored -> { }).ref(null));
        assertThrows(NullPointerException.class,
                () -> modal("good", "Title", "close", ignored -> { }).attr(null, "value"));
        assertThrows(IllegalArgumentException.class,
                () -> modal("good", "Title", "close", ignored -> { }).attr("open", true));
        assertThrows(IllegalArgumentException.class,
                () -> modal("good", "Title", "close", ignored -> { }).attr("data-roots-ref", "unsafe"));
        assertThrows(IllegalArgumentException.class,
                () -> modal("good", "Title", "close", ignored -> { }).attr("bad name", "unsafe"));
        assertThrows(IllegalArgumentException.class,
                () -> modal("good", "Title", "close", ignored -> { }).attr("onclick", "bad"));
    }

    @Test
    void validatesPortalIdentityNestingAndBoundaryRollback() {
        assertThrows(IllegalArgumentException.class, () -> portal("bad portal", "content"));
        assertThrows(IllegalStateException.class,
                () -> HtmlRenderer.render(div(portal("same", "one"), portal("same", "two")), context));
        assertThrows(IllegalStateException.class,
                () -> HtmlRenderer.render(portal("outer", portal("inner", "nested")), context));

        Component broken = page -> {
            throw new IllegalStateException("portal failed");
        };
        var recovered = HtmlRenderer.render(
                boundary(portal("overlay", broken), (error, page) -> portal("overlay", "Recovered")),
                context
        );
        assertEquals("<template data-roots-portal=\"overlay\">Recovered</template>", recovered.html());
    }

    private static final class Counter implements Component {
        private int count;

        @Override
        public Node render(PageContext context) {
            return button(count).onClick(this, "increment");
        }

        @ServerAction
        private void increment() {
            count++;
        }
    }
}
