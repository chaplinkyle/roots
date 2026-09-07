package com.chaplin.roots.spring.testapp.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;
import com.chaplin.roots.spring.testapp.GreetingService;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.atomic.AtomicInteger;

import static com.chaplin.roots.html.Html.div;

public final class Layout implements com.chaplin.roots.Layout, DisposableBean {
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
