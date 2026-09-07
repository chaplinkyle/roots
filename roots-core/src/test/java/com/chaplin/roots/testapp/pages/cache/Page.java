package com.chaplin.roots.testapp.pages.cache;

import com.chaplin.roots.CachePolicy;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static com.chaplin.roots.html.Html.main;

public final class Page implements com.chaplin.roots.Page {
    private static final AtomicInteger LOADS = new AtomicInteger();
    private final String dependency;

    public Page(String dependency) {
        this.dependency = dependency;
    }

    public static void reset() {
        LOADS.set(0);
    }

    @Override
    public Node render(PageContext context) {
        return main(context.cache().get(
                "fixture:page-cache",
                String.class,
                CachePolicy.tagged(Duration.ofMinutes(1), "fixture-cache"),
                () -> dependency + ":" + LOADS.incrementAndGet()
        ));
    }
}
