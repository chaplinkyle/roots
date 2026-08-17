package dev.roots.testapp.api.blocking;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class Route implements ApiRoute {
    private static volatile CountDownLatch entered = new CountDownLatch(1);
    private static volatile CountDownLatch released = new CountDownLatch(1);

    public Route(String ignored) {
    }

    @Override
    public Response get(Request request) throws InterruptedException {
        entered.countDown();
        if (!released.await(5, TimeUnit.SECONDS)) {
            return Response.text(504, "blocking fixture timed out");
        }
        return Response.text(200, "completed");
    }

    public static void reset() {
        entered = new CountDownLatch(1);
        released = new CountDownLatch(1);
    }

    public static boolean awaitEntry() throws InterruptedException {
        return entered.await(2, TimeUnit.SECONDS);
    }

    public static void release() {
        released.countDown();
    }
}
