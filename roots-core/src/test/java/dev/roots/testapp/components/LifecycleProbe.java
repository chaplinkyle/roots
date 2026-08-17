package dev.roots.testapp.components;

import dev.roots.Component;
import dev.roots.PageContext;
import dev.roots.html.Node;

import java.util.concurrent.atomic.AtomicInteger;

import static dev.roots.html.Html.span;

public final class LifecycleProbe implements Component {
    public static final AtomicInteger MOUNTS = new AtomicInteger();
    public static final AtomicInteger UNMOUNTS = new AtomicInteger();

    private final String name;
    private final boolean failOnUnmount;

    public LifecycleProbe(String name, boolean failOnUnmount) {
        this.name = name;
        this.failOnUnmount = failOnUnmount;
    }

    @Override
    public Node render(PageContext context) {
        return span(name);
    }

    @Override
    public void onMount(PageContext context) {
        MOUNTS.incrementAndGet();
    }

    @Override
    public void onUnmount(PageContext context) {
        UNMOUNTS.incrementAndGet();
        if (failOnUnmount) {
            throw new IllegalStateException("expected lifecycle probe failure");
        }
    }

    public static void reset() {
        MOUNTS.set(0);
        UNMOUNTS.set(0);
    }
}
