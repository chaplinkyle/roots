package com.chaplin.roots.examples.servlet.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.*;

/** Anonymous in-memory deployment probe, not the persistent business workflow. */
public final class Page implements com.chaplin.roots.Page {
    private int count;

    @Override
    public Node render(PageContext context) {
        return main(h1("Roots in your Servlet container"),
                p("Application mount: " + context.mountPath()),
                p("This anonymous counter is an in-memory deployment example."),
                p("Count: ", strong(count).id("count")),
                button("Increment").type("button").onClick(this, "increment"));
    }

    @ServerAction
    private void increment() { count++; }
}
