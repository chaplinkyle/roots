package com.chaplin.roots.jdbc.fixture.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static com.chaplin.roots.html.Html.h1;

public final class Page implements com.chaplin.roots.Page {
    private static final AtomicInteger CACHE_LOADS = new AtomicInteger();

    @Override
    public Node render(PageContext context) {
        var visits = context.session().get("visits", Integer.class).orElse(0) + 1;
        context.session().put("visits", visits);
        var shared = context.cache().get("jdbc-fixture", Integer.class, Duration.ofMinutes(1),
                CACHE_LOADS::incrementAndGet);
        return h1("JDBC cluster fixture visit ", visits, ", shared load ", shared);
    }

    public static void resetCacheLoads() {
        CACHE_LOADS.set(0);
    }

    public static int cacheLoads() {
        return CACHE_LOADS.get();
    }
}
