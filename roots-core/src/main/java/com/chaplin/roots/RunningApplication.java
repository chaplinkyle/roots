package com.chaplin.roots;

import com.chaplin.roots.internal.RootsServer;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** A controllable handle to a running embedded Roots server. */
public final class RunningApplication implements AutoCloseable {
    private final RootsServer server;
    private final CountDownLatch stopped = new CountDownLatch(1);

    /** Wraps an initialized server.
     * @param server embedded server */
    public RunningApplication(RootsServer server) {
        this.server = server;
    }

    /** Returns the listening address.
     * @return application URI */
    public URI uri() {
        return server.uri();
    }

    /** Returns discovered route descriptions.
     * @return immutable routes */
    public List<String> routes() {
        return server.routes();
    }

    /** Returns current built-in server counters without mutating application state.
     * @return runtime snapshot */
    public RuntimeSnapshot runtimeSnapshot() {
        return server.runtimeSnapshot();
    }

    /** Returns the application-scoped cache for jobs and explicit revalidation.
     * @return application cache */
    public RootsCache cache() {
        return server.cache();
    }

    /** Returns the live-view affinity identifier advertised by this runtime.
     * @return node identifier */
    public String nodeId() {
        return server.nodeId();
    }

    /** Blocks until this handle is closed.
     * @throws InterruptedException if the waiting thread is interrupted */
    public void await() throws InterruptedException {
        stopped.await();
    }

    /** Marks readiness down and rejects new work while keeping the listener available for health checks. */
    public void beginDrain() {
        server.beginDrain();
    }

    /** Stops accepting work and gives current exchanges up to {@code timeout} to complete.
     * @param timeout maximum graceful wait */
    public void closeGracefully(Duration timeout) {
        server.closeGracefully(timeout);
        stopped.countDown();
    }

    @Override
    public void close() {
        server.close();
        stopped.countDown();
    }
}
