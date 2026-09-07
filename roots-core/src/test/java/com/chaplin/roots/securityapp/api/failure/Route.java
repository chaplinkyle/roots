package com.chaplin.roots.securityapp.api.failure;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;
import com.chaplin.roots.securityapp.FixtureFailure;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        throw new FixtureFailure("mapped HTML failure");
    }
}
