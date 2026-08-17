package dev.roots.errorpageapp.api.failure;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        throw new IllegalStateException("expected API failure");
    }
}
