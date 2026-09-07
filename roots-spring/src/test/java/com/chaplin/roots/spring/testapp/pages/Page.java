package com.chaplin.roots.spring.testapp.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;
import com.chaplin.roots.spring.testapp.GreetingService;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.atomic.AtomicInteger;

import static com.chaplin.roots.html.Html.main;

public final class Page implements com.chaplin.roots.Page, DisposableBean {
    private static final AtomicInteger DESTROYED = new AtomicInteger();
    private final GreetingService greetings;

    public Page(GreetingService greetings) {
        this.greetings = greetings;
    }

    @Override
    public Node render(PageContext context) {
        return main(greetings.greeting("Spring page"));
    }

    @Override
    public void destroy() {
        DESTROYED.incrementAndGet();
    }

    public static void resetDestroyed() {
        DESTROYED.set(0);
    }

    public static int destroyed() {
        return DESTROYED.get();
    }
}
