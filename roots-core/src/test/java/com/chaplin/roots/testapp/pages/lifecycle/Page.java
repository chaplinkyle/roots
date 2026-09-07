package com.chaplin.roots.testapp.pages.lifecycle;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;
import com.chaplin.roots.testapp.components.LifecycleProbe;

import java.util.concurrent.atomic.AtomicInteger;

import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.button;

public final class Page implements com.chaplin.roots.Page {
    public static final AtomicInteger MOUNTS = new AtomicInteger();
    public static final AtomicInteger UNMOUNTS = new AtomicInteger();

    private final LifecycleProbe failing = new LifecycleProbe("failing", true);
    private final LifecycleProbe healthy = new LifecycleProbe("healthy", false);
    private boolean probesVisible = true;

    public Page(String ignored) {
    }

    @Override
    public Node render(PageContext context) {
        return div(
                button("Remove probes").onClick(this, "removeProbes"),
                probesVisible ? div(failing, healthy) : null
        );
    }

    @ServerAction
    private void removeProbes() {
        probesVisible = false;
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
