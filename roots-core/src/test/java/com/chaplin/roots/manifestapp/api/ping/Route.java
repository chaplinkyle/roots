package com.chaplin.roots.manifestapp.api.ping;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        return Response.text(200, "pong");
    }
}
