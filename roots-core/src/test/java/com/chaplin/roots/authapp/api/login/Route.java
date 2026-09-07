package com.chaplin.roots.authapp.api.login;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        setOrRemove(request, "role");
        setOrRemove(request, "project");
        request.session().put("write", request.queryValue("write").map(Boolean::parseBoolean).orElse(false));
        return Response.noContent();
    }

    private static void setOrRemove(Request request, String name) {
        var value = request.queryValue(name);
        if (value.isPresent()) {
            request.session().put(name, value.orElseThrow());
        } else {
            request.session().remove(name);
        }
    }
}
