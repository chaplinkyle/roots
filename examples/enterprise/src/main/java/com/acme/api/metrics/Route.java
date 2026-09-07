package com.acme.api.metrics;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.CachePolicy;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Demonstrates cached service data and explicit tag revalidation. */
public final class Route implements ApiRoute {
    private static final AtomicInteger DATABASE_QUERIES = new AtomicInteger();

    @Override
    public Response get(Request request) {
        var json = request.cache().get(
                "operations:metrics",
                String.class,
                CachePolicy.tagged(Duration.ofMinutes(5), "operations-dashboard"),
                () -> "{\"requests\":84291,\"databaseQuery\":" + DATABASE_QUERIES.incrementAndGet() + "}"
        );
        return Response.json(200, json);
    }

    @Override
    public Response delete(Request request) {
        var invalidated = request.cache().invalidateTag("operations-dashboard");
        return Response.json(200, "{\"invalidated\":" + invalidated + "}");
    }
}
