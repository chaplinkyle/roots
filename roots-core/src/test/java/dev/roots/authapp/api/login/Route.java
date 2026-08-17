package dev.roots.authapp.api.login;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;

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
