package com.chaplin.roots.spring.boot;

import com.chaplin.roots.servlet.RootsServlet;
import org.springframework.context.SmartLifecycle;

/** Drains Roots before Boot waits for its container's long-lived SSE requests. */
final class RootsServletLifecycle implements SmartLifecycle {
    private static final System.Logger LOG = System.getLogger(RootsServletLifecycle.class.getName());
    private final RootsServlet servlet;
    private volatile boolean running;

    RootsServletLifecycle(RootsServlet servlet) { this.servlet = servlet; }
    @Override public void start() { running = true; }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public void stop() {
        try { servlet.destroy(); }
        finally { running = false; }
    }
    @Override public void stop(Runnable callback) {
        Thread.startVirtualThread(() -> {
            try { stop(); }
            catch (RuntimeException | Error failure) { LOG.log(System.Logger.Level.ERROR, "Roots servlet drain failed", failure); }
            finally { callback.run(); }
        });
    }
}
