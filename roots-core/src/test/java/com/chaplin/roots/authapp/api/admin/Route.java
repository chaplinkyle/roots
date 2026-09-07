package com.chaplin.roots.authapp.api.admin;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;
import com.chaplin.roots.annotation.Authorize;

@Authorize("staff")
public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        return Response.json(200, "{\"authorized\":true}");
    }
}
