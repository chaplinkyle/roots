package com.chaplin.roots.browser.pages.widgets;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;
import static com.chaplin.roots.html.Html.*;

public final class Page implements com.chaplin.roots.Page {
    public static final java.util.Map<String, Runnable> UPDATES = new java.util.concurrent.ConcurrentHashMap<>();
    private Runnable updater;
    private int value;
    private boolean visible = true;
    private boolean reversed;
    private boolean slow;
    private boolean fail;
    private String module = "/widgets/probe.js";
    private String saved = "";
    private final Scoped scoped = new Scoped();

    @Override public void onMount(PageContext context) {
        updater = () -> context.update(() -> value++);
        context.query("probe").ifPresent(key -> UPDATES.put(key, updater));
    }
    @Override public void onUnmount(PageContext context) {
        context.query("probe").ifPresent(key -> UPDATES.remove(key, updater));
    }

    @Override public Node render(PageContext context) {
        var first = div(p("Fallback ", value)).id("widget-first").widget("first", module)
                .data("widget-value", value).data("widget-slow", slow).data("widget-fail", fail)
                .data("widget-wait-mount", context.query("wait").orElse("false"));
        var second = div(p("Second fallback")).id("widget-second").widget("second", "/widgets/probe.js")
                .data("widget-value", 100);
        return main(h1("Widget integration"),
                div(visible ? (reversed ? fragment(second, first) : fragment(first, second)) : second).id("widget-list"),
                button("Update").id("widget-update").onClick(this, "increment"),
                button("Reverse").id("widget-reverse").onClick(this, "reverse"),
                button("Toggle").id("widget-toggle").onClick(this, "toggle"),
                button("Slow").id("widget-slow").onClick(this, "slow"),
                button("Fail").id("widget-fail").onClick(this, "fail"),
                button("Change module").id("widget-module").onClick(this, "module"),
                button("Missing module").id("widget-missing").onClick(this, "missing"),
                form(input().type("hidden").id("widget-bridge").name("draft").value(saved),
                        button("Save draft").type("submit").id("widget-save"), validationSummary())
                        .onSubmit(this, "save"),
                p(saved).id("widget-saved"),
                context.query("scoped").isPresent() ? fragment(scoped,
                        button("Scoped update").id("widget-scoped-update").onClick(this, "scopedUpdate"),
                        button("Scoped tag").id("widget-scoped-tag").onClick(this, "scopedTag")) : fragment(),
                context.query("portal").isPresent() && visible
                        ? portal("widget-portal", div(p("Portal fallback")).id("widget-portal").widget("portal", "/widgets/probe.js").data("widget-value", value))
                        : fragment(),
                span(value).id("widget-server-value"), link("/widgets", "Fresh widgets").id("widget-fresh"),
                link("/", "Leave widgets").id("widget-leave"));
    }
    @ServerAction private void increment() { value++; }
    @ServerAction private void reverse() { reversed = !reversed; }
    @ServerAction private void toggle() { visible = !visible; }
    @ServerAction private void slow() { slow = true; value++; }
    @ServerAction private void fail() { fail = true; value++; }
    @ServerAction private void module() { module = "/widgets/probe.js?v=2"; }
    @ServerAction private void missing() { module = "/widgets/missing.js"; }
    @ServerAction private void save(com.chaplin.roots.ActionEvent event) {
        saved = event.bind(Draft.class).draft();
    }
    @com.chaplin.roots.validation.FormModel
    public record Draft(@com.chaplin.roots.validation.NotBlank String draft) {}
    @ServerAction private void scopedUpdate() { scoped.value++; }
    @ServerAction private void scopedTag() { scoped.section = !scoped.section; }
    private static final class Scoped implements com.chaplin.roots.Component {
        int value;
        boolean section;
        public Node render(PageContext context) {
            return (section ? section(p("Scoped fallback")) : div(p("Scoped fallback")))
                    .id("widget-scoped").widget("scoped", "/widgets/probe.js").data("widget-value", value);
        }
    }
}
