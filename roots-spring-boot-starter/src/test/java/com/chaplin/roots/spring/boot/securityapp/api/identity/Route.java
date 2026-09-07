package com.chaplin.roots.spring.boot.securityapp.api.identity;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        return Response.json(200, request.identity()
                .map(identity -> "{\"name\":\"" + identity.name() + "\",\"authorities\":\""
                        + String.join(",", identity.authorities().stream().sorted().toList()) + "\"}")
                .orElse("{\"name\":\"anonymous\"}"));
    }
}
