package dev.roots.spring.testapp.pages;

import dev.roots.PageContext;
import dev.roots.html.Node;
import dev.roots.spring.testapp.GreetingService;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.atomic.AtomicInteger;

import static dev.roots.html.Html.div;

public final class Layout implements dev.roots.Layout, DisposableBean {
    private static final AtomicInteger DESTROYED = new AtomicInteger();
    private final GreetingService greetings;

    public Layout(GreetingService greetings) {
        this.greetings = greetings;
    }

    @Override
    public Node render(PageContext context, Node children) {
        return div(greetings.greeting("Spring layout"), children);
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
