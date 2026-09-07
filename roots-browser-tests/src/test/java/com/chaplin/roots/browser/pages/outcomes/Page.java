package com.chaplin.roots.browser.pages.outcomes;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;
import java.util.concurrent.atomic.AtomicInteger;
import static com.chaplin.roots.html.Html.*;

public final class Page implements com.chaplin.roots.Page {
    public static final AtomicInteger WRITES = new AtomicInteger();
    private String mode;
    private int writes;

    @Override
    public Node render(PageContext context) {
        mode = context.query("mode").orElse("ok");
        if (writes > 0 && mode.equals("render")) throw new IllegalStateException("render failed after business write");
        return main(h1("Action outcome recovery"),
                link("/outcomes?mode=ok", "Check a fresh view").id("outcome-fresh"),
                form(label("Draft", input().name("draft").id("outcome-draft"))),
                button("Write").id("outcome-write").onClick(this, "write"),
                p(writes).id("outcome-writes"));
    }

    @ServerAction
    private void write() {
        WRITES.incrementAndGet();
        writes++;
        if (mode.equals("handler")) throw new IllegalStateException("handler failed after business write");
        if (mode.equals("mapped")) throw new MappedFailure();
    }

    public static final class MappedFailure extends RuntimeException { }
}
