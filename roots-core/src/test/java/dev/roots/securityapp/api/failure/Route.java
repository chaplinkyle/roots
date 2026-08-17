package dev.roots.securityapp.api.failure;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;
import dev.roots.securityapp.FixtureFailure;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        throw new FixtureFailure("mapped HTML failure");
    }
}
