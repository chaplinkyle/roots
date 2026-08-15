package dev.roots;

import dev.roots.internal.RootsServer;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class RunningApplication implements AutoCloseable {
    private final RootsServer server;
    private final CountDownLatch stopped = new CountDownLatch(1);

    public RunningApplication(RootsServer server) {
        this.server = server;
    }

    public URI uri() {
        return server.uri();
    }

    public List<String> routes() {
        return server.routes();
    }

    public void await() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        server.close();
        stopped.countDown();
    }
}
