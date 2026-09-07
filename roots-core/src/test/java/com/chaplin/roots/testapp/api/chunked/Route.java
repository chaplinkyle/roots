package com.chaplin.roots.testapp.api.chunked;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;

import java.nio.charset.StandardCharsets;

/** Unknown-length streaming API fixture. */
public final class Route implements ApiRoute {
    /** Creates the fixture. */
    public Route() {
    }

    /** Creates the fixture through the integration-test instance factory.
     * @param ignored injected test dependency */
    public Route(String ignored) {
    }

    @Override
    public Response get(Request request) {
        return Response.stream(200, "text/plain; charset=utf-8", output -> {
            output.write("first-".getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.write("second".getBytes(StandardCharsets.UTF_8));
        }).withHeader("X-Roots-Streaming", "chunked");
    }
}
