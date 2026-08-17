package dev.roots.securityapp.api.html;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        return Response.html(200, "<!doctype html><title>API HTML</title>")
                .withHeader("Content-Security-Policy", "default-src *");
    }
}
