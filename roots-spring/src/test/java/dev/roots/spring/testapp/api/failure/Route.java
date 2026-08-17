package dev.roots.spring.testapp.api.failure;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.atomic.AtomicInteger;

public final class Route implements ApiRoute, DisposableBean {
    private static final AtomicInteger DESTROYED = new AtomicInteger();

    @Override
    public Response get(Request request) {
        throw new IllegalStateException("expected Spring route failure");
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
