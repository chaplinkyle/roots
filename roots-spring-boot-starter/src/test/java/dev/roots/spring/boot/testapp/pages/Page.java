package dev.roots.spring.boot.testapp.pages;

import dev.roots.PageContext;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;
import dev.roots.spring.boot.testapp.GreetingService;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.atomic.AtomicInteger;

import static dev.roots.html.Html.main;
import static dev.roots.html.Html.link;
import static dev.roots.html.Html.button;

public final class Page implements dev.roots.Page, DisposableBean {
    private static final AtomicInteger DESTROYED = new AtomicInteger();
    private final GreetingService greetings;
    private int count;

    public Page(GreetingService greetings) {
        this.greetings = greetings;
    }

    @Override
    public Node render(PageContext context) {
        return main(greetings.greeting("Boot page"), link("/", "Home"),
                button("Count ", count).onClick(this, "increment"));
    }

    @ServerAction
    private void increment() {
        count++;
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
