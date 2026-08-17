package dev.roots.spring.testapp.api.greeting;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;
import dev.roots.spring.testapp.GreetingService;
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
        return Response.text(200, greetings.greeting("Spring route"));
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
