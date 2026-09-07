package com.chaplin.roots.errorpageapp.api.failure;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        throw new IllegalStateException("expected API failure");
    }
}
