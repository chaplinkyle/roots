package com.chaplin.roots.statelessapp.api.items;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.annotation.Stateless;

@Stateless
@Authorize("authenticated")
public final class Route implements ApiRoute {
    @Override public Response get(Request request) {
        return Response.text(200, request.identity().orElseThrow().name());
    }
    @Override public Response post(Request request) {
        request.session().put("not allowed", "value");
        return Response.noContent();
    }
}
