package com.chaplin.roots.testapp.api.cache;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.CachePolicy;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public final class Route implements ApiRoute {
    private static final AtomicInteger LOADS = new AtomicInteger();
    private final String dependency;

    public Route(String dependency) {
        this.dependency = dependency;
    }

    public static void reset() {
        LOADS.set(0);
    }

    @Override
    public Response get(Request request) {
        var value = request.cache().get(
                "fixture:api-cache",
                String.class,
                CachePolicy.tagged(Duration.ofMinutes(1), "fixture-cache"),
                () -> dependency + ":" + LOADS.incrementAndGet()
        );
        return Response.text(200, value);
    }
}
