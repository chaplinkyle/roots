package com.chaplin.roots.testapp.api.failure;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;

public final class Route implements ApiRoute {
    public Route(String ignored) {
    }

    @Override
    public Response get(Request request) {
        throw new IllegalStateException("fixture exploded");
    }
}
