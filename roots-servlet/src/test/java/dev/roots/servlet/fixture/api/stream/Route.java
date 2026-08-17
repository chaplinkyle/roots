package dev.roots.servlet.fixture.api.stream;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Servlet streaming-response fixture. */
public final class Route implements ApiRoute {
    private static final byte[] CONTENT = "servlet-stream-content".getBytes(StandardCharsets.UTF_8);
    private static final AtomicInteger WRITES = new AtomicInteger();

    /** Creates the route. */
    public Route() {
    }

    /** Resets writer observations. */
    public static void reset() {
        WRITES.set(0);
    }

    /** Returns writer invocations.
     * @return invocation count */
    public static int writes() {
        return WRITES.get();
    }

    /** Returns exact fixture size.
     * @return byte count */
    public static int length() {
        return CONTENT.length;
    }

    @Override
    public Response get(Request request) {
        return Response.stream(200, "application/octet-stream", CONTENT.length, output -> {
            WRITES.incrementAndGet();
            output.write(CONTENT);
        });
    }
}
