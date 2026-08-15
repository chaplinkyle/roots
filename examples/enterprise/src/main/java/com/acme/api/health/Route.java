package com.acme.api.health;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;

import java.time.Instant;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        return Response.json(200, """
                {"status":"ok","runtime":"java","framework":"roots","time":"%s"}
                """.formatted(Instant.now()));
    }
}
