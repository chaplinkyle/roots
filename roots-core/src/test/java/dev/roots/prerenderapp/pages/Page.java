package dev.roots.prerenderapp.pages;

import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.annotation.Prerender;
import dev.roots.html.Node;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.main;

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
public final class Page implements dev.roots.Page {
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
