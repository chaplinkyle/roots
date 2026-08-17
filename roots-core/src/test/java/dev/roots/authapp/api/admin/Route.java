package dev.roots.authapp.api.admin;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;
import dev.roots.annotation.Authorize;

@Authorize("staff")
public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        return Response.json(200, "{\"authorized\":true}");
    }
}
