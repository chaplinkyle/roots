package com.chaplin.roots.spring.testapp.api.failure;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;
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
