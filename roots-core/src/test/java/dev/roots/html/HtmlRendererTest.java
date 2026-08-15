package dev.roots.html;

import dev.roots.ActionEvent;
import dev.roots.Component;
import dev.roots.PageContext;
import dev.roots.Ref;
import dev.roots.Session;
import dev.roots.annotation.ServerAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.roots.html.Html.boundary;
import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.span;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HtmlRendererTest {
    private final PageContext context = new PageContext("/", Map.of(), Map.of(), new Session("test"));

    @Test
    void escapesTextAndAttributesByDefault() {
        var rendered = HtmlRenderer.render(
                div("<script>alert('no')</script>").attr("title", "\"<&"),
                context
        );

        assertEquals("<div title=\"&quot;&lt;&amp;\">&lt;script&gt;alert('no')&lt;/script&gt;</div>", rendered.html());
    }

    @Test
    void composesComponentsAndEmitsStableKeys() {
        Component badge = page -> span("ready").className("badge").key("health");

        var rendered = HtmlRenderer.render(div("Status: ", badge), context);

        assertEquals("<div>Status: <span class=\"badge\" data-roots-key=\"health\">ready</span></div>", rendered.html());
        assertEquals(List.of(badge), rendered.components());
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
