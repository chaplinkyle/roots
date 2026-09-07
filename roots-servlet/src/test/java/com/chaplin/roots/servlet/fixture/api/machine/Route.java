package com.chaplin.roots.servlet.fixture.api.machine;

import com.chaplin.roots.Request;
import com.chaplin.roots.Response;
import com.chaplin.roots.annotation.Stateless;

@Stateless
public final class Route implements com.chaplin.roots.ApiRoute {
    @Override
    public Response get(Request request) {
        return request.identity().map(identity -> Response.text(200, identity.name()))
                .orElseGet(() -> Response.text(401, "Authentication required"));
    }
}
