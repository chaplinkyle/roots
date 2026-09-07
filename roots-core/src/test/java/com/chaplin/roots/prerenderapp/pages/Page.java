package com.chaplin.roots.prerenderapp.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.Prerender;
import com.chaplin.roots.html.Node;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.main;

@Prerender(revalidateSeconds = 1)
@PageMetadata(
        title = "Prerender fixture",
        description = "Static fixture",
        stylesheets = "/static.css",
        canonical = "https://example.com/",
        robots = "index, follow",
        themeColor = "#102030",
        openGraphType = "website",
        openGraphImage = "https://example.com/static-preview.png",
        openGraphImageAlt = "Static preview"
)
public final class Page implements com.chaplin.roots.Page {
    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static final AtomicBoolean FAIL_NEXT = new AtomicBoolean();

    @Override
    public Node render(PageContext context) {
        var generation = GENERATIONS.incrementAndGet();
        if (FAIL_NEXT.compareAndSet(true, false)) {
            throw new IllegalStateException("expected regeneration failure");
        }
        try {
            Thread.sleep(150);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Prerender interrupted", exception);
        }
        return main(h1("Generation " + generation), "Static home");
    }

    public static void reset() {
        GENERATIONS.set(0);
        FAIL_NEXT.set(false);
    }

    public static int generations() {
        return GENERATIONS.get();
    }

    public static void failNext() {
        FAIL_NEXT.set(true);
    }
}
