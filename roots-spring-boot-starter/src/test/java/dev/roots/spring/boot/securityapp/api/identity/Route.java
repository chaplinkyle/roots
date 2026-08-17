package dev.roots.spring.boot.securityapp.api.identity;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        return Response.json(200, request.identity()
                .map(identity -> "{\"name\":\"" + identity.name() + "\",\"authorities\":\""
                        + String.join(",", identity.authorities().stream().sorted().toList()) + "\"}")
                .orElse("{\"name\":\"anonymous\"}"));
    }
}
