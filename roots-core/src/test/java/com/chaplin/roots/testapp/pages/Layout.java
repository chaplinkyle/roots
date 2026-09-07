package com.chaplin.roots.testapp.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import java.util.concurrent.atomic.AtomicInteger;

import static com.chaplin.roots.html.Html.div;

public final class Layout implements com.chaplin.roots.Layout {
    public static final AtomicInteger MOUNTS = new AtomicInteger();
    public static final AtomicInteger UNMOUNTS = new AtomicInteger();

    private final String marker;

    public Layout(String marker) {
        this.marker = marker;
    }

    @Override
    public Node render(PageContext context, Node children) {
        return div(children).data("layout", marker);
    }

    @Override
    public void onMount(PageContext context) {
        MOUNTS.incrementAndGet();
    }

    @Override
    public void onUnmount(PageContext context) {
        UNMOUNTS.incrementAndGet();
    }

    public static void reset() {
        MOUNTS.set(0);
        UNMOUNTS.set(0);
    }
}
