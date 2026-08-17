package dev.roots.spring.testapp.pages;

import dev.roots.PageContext;
import dev.roots.html.Node;
import dev.roots.spring.testapp.GreetingService;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.atomic.AtomicInteger;

import static dev.roots.html.Html.main;

public final class Page implements dev.roots.Page, DisposableBean {
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
