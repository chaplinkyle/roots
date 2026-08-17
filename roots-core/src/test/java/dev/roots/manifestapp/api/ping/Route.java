package dev.roots.manifestapp.api.ping;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        return Response.text(200, "pong");
    }
}
