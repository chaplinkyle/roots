package dev.roots.testapp.api.failure;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;

public final class Route implements ApiRoute {
    public Route(String ignored) {
    }

    @Override
    public Response get(Request request) {
        throw new IllegalStateException("fixture exploded");
    }
}
