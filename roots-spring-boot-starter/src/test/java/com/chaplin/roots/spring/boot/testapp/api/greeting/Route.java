package com.chaplin.roots.spring.boot.testapp.api.greeting;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;
import com.chaplin.roots.spring.boot.testapp.GreetingService;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.atomic.AtomicInteger;

public final class Route implements ApiRoute, DisposableBean {
    private static final AtomicInteger DESTROYED = new AtomicInteger();
    private final GreetingService greetings;

    public Route(GreetingService greetings) {
        this.greetings = greetings;
    }

    @Override
    public Response get(Request request) {
        return Response.text(200, greetings.greeting("Boot route"));
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
