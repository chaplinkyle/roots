package com.chaplin.roots.browser.pages.forms;

import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;
import java.util.concurrent.atomic.AtomicReference;
import static com.chaplin.roots.html.Html.*;

public final class Page implements com.chaplin.roots.Page {
    private static final AtomicReference<Page> MOUNTED = new AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicInteger UPDATE_ACTIONS = new java.util.concurrent.atomic.AtomicInteger();
    private PageContext context;
    private String query = "";
    private String saved = "";
    private int updates;

    @Override
    public Node render(PageContext context) {
        return main(
                h1("Form preservation"),
                link("/forms?fresh=true", "Open a fresh form").id("fresh-form"),
                form(
                        input().id("draft").name("draft"),
                        textarea().id("notes").name("notes"),
                        input().id("enabled").type("checkbox").name("enabled").value("yes"),
                        select(option("One").value("one"), option("Two").value("two"),
                                option("Three").value("three"))
                                .id("choices").name("choices").attr("multiple", true),
                        input().id("file").type("file").name("file"),
                        button("Save").type("submit").id("save")
                ).id("draft-form").onSubmit(this, "save"),
                input().id("query").name("query").value(query).onInput(this, "search"),
                button("Update other region").id("update").onClick(this, "update"),
                p(updates).id("updates"), p(saved).id("saved"), p(query).id("query-result")
        );
    }

    @Override
    public void onMount(PageContext context) {
        this.context = context;
        MOUNTED.set(this);
    }

    @Override
    public void onUnmount(PageContext context) {
        MOUNTED.compareAndSet(this, null);
    }

    public static void push() {
        var page = MOUNTED.get();
        page.context.update(() -> page.updates++);
    }

    public static int updateActions() {
        return UPDATE_ACTIONS.get();
    }

    @ServerAction
    private void search(ActionEvent event) {
        query = event.required("query");
    }

    @ServerAction
    private void save(ActionEvent event) {
        saved = event.required("draft");
    }

    @ServerAction
    private void update() {
        updates++;
        UPDATE_ACTIONS.incrementAndGet();
    }
}
