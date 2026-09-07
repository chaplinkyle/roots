package com.chaplin.roots.browser.pages.slow;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.html.Node;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.main;

@PageMetadata(title = "Roots slow fixture")
public final class Page implements com.chaplin.roots.Page {
    private static final AtomicReference<CountDownLatch> RENDER_GATE =
            new AtomicReference<>(new CountDownLatch(0));

    public static void hold() {
        RENDER_GATE.getAndSet(new CountDownLatch(1)).countDown();
    }

    public static void release() {
        RENDER_GATE.getAndSet(new CountDownLatch(0)).countDown();
    }

    @Override
    public Node render(PageContext context) {
        try {
            if (!RENDER_GATE.get().await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Browser test did not release the slow render gate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("slow browser fixture interrupted", interrupted);
        }
        return main(
                h1("Slow page").id("slow-page"),
                link("/", "Home").id("slow-home")
        );
    }
}
