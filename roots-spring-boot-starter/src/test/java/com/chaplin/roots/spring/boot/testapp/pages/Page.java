package com.chaplin.roots.spring.boot.testapp.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;
import com.chaplin.roots.spring.boot.testapp.GreetingService;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.atomic.AtomicInteger;

import static com.chaplin.roots.html.Html.main;
import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.button;

public final class Page implements com.chaplin.roots.Page, DisposableBean {
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
