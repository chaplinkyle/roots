package com.chaplin.roots;

import com.chaplin.roots.testapp.api.blocking.Route;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RootsRunTest {
    @Test
    void interruptionDrainsAnInflightApiRequestBeforeClosingTheListener() throws Exception {
        final int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Route.reset();
        var failure = new AtomicReference<Throwable>();
        var config = RootsConfig.forApplication(com.chaplin.roots.testapp.Application.class).port(port).development(false)
                .instanceFactory(type -> type == Route.class ? new Route("test") : InstanceFactory.reflection().create(type))
                .build();
        var thread = Thread.ofPlatform().start(() -> {
            try { Roots.run(config, Duration.ofSeconds(3)); }
            catch (Throwable error) { failure.set(error); }
        });
        var base = URI.create("http://127.0.0.1:" + port);
        try (var client = HttpClient.newHttpClient()) {
            var health = HttpRequest.newBuilder(base.resolve("/_roots/health")).timeout(Duration.ofSeconds(1)).build();
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                try {
                    if (client.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) break;
                } catch (java.io.IOException starting) { }
                assertTrue(System.nanoTime() < deadline, "Listener did not become ready: " + failure.get());
                Thread.sleep(10);
            }
            var response = client.sendAsync(HttpRequest.newBuilder(base.resolve("/api/blocking")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(Route.awaitEntry());
            thread.interrupt();
            while (client.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() != 503) {
                assertTrue(System.nanoTime() < deadline, "Shutdown did not lower readiness");
                Thread.sleep(10);
            }
            Route.release();
            assertEquals(200, response.get(2, TimeUnit.SECONDS).statusCode());
            assertEquals("completed", response.get().body());
            thread.join(4000);
            assertFalse(thread.isAlive());
            assertNull(failure.get());
        } finally {
            Route.release();
            thread.interrupt();
            thread.join(4000);
        }
    }
}
