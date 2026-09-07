package com.chaplin.roots.securityapp.api.html;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        return Response.html(200, "<!doctype html><title>API HTML</title>")
                .withHeader("Content-Security-Policy", "default-src *");
    }
}
