package dev.roots.testapp.api.items.$itemId;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;

public final class Route implements ApiRoute {
    private final String injected;

    public Route(String injected) {
        this.injected = injected;
    }

    @Override
    public Response get(Request request) {
        return Response.json(200, "{\"item\":\"" + request.parameters().get("itemId")
                + "\",\"injected\":\"" + injected + "\"}");
    }

    @Override
    public Response post(Request request) {
        return Response.text(201, request.formValue("name").orElse("missing"));
    }
}
