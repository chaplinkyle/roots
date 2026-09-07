package com.acme.api.health;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;

import java.time.Instant;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        return Response.json(200, """
                {"status":"ok","runtime":"java","framework":"roots","time":"%s"}
                """.formatted(Instant.now()));
    }
}
