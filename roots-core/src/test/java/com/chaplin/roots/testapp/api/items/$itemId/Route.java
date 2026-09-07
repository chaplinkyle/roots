package com.chaplin.roots.testapp.api.items.$itemId;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;

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
