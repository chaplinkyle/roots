package com.chaplin.roots.testapp.api.stream;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed-length streaming API fixture. */
public final class Route implements ApiRoute {
    /** Exact fixture byte count. */
    public static final int LENGTH = 262_144;
    private static final AtomicInteger WRITES = new AtomicInteger();

    /** Creates the fixture. */
    public Route() {
    }

    /** Creates the fixture through the integration-test instance factory.
     * @param ignored injected test dependency */
    public Route(String ignored) {
    }

    /** Resets lazy-writer observations. */
    public static void reset() {
        WRITES.set(0);
    }

    /** Returns writer invocation count.
     * @return invocations */
    public static int writes() {
        return WRITES.get();
    }

    @Override
    public Response get(Request request) {
        return Response.stream(200, "application/octet-stream", LENGTH, output -> {
            WRITES.incrementAndGet();
            var block = new byte[1_024];
            Arrays.fill(block, (byte) 'R');
            for (var offset = 0; offset < LENGTH; offset += block.length) {
                output.write(block);
            }
        }).withHeader("X-Roots-Streaming", "fixed");
    }
}
