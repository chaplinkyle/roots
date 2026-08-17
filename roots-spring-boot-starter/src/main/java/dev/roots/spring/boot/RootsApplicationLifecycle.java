package dev.roots.spring.boot;

import dev.roots.Roots;
import dev.roots.RootsConfig;
import dev.roots.RuntimeSnapshot;
import dev.roots.RunningApplication;
import org.springframework.context.SmartLifecycle;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Spring-owned lifecycle for one embedded Roots server.
 *
 * <p>The server starts after the Spring context has created its singletons. It
 * drains before lower-phase application services are destroyed.</p>
 */
public final class RootsApplicationLifecycle implements SmartLifecycle, RootsRuntime {
    private final RootsConfig config;
    private final Duration shutdownTimeout;
    private volatile RunningApplication application;
    private volatile long completedRequests;
    private volatile long rejectedRequests;
    private volatile int peakActiveRequests;

    /**
     * Creates a lifecycle from validated Roots configuration.
     *
     * @param config Roots server configuration
     * @param shutdownTimeout maximum graceful shutdown wait
     */
    public RootsApplicationLifecycle(RootsConfig config, Duration shutdownTimeout) {
        this.config = Objects.requireNonNull(config, "config");
        this.shutdownTimeout = requirePositive(shutdownTimeout);
    }

    @Override
    public synchronized void start() {
        if (application == null) {
            application = Roots.start(config);
        }
    }

    @Override
    public synchronized void stop() {
        var running = application;
        if (running != null) {
            running.beginDrain();
            try {
                running.closeGracefully(shutdownTimeout);
            } finally {
                var finalSnapshot = running.runtimeSnapshot();
                completedRequests += finalSnapshot.handledRequests();
                rejectedRequests += finalSnapshot.rejectedRequests();
                peakActiveRequests = Math.max(peakActiveRequests, finalSnapshot.peakActiveRequests());
                application = null;
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return application != null;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /**
     * Returns the running server handle.
     *
     * @return active Roots application
     * @throws IllegalStateException if the Spring lifecycle is not running
     */
    public RunningApplication application() {
        var running = application;
        if (running == null) {
            throw new IllegalStateException("Roots is not running");
        }
        return running;
    }

    /**
     * Returns the current listener address.
     *
     * @return active Roots URI
     * @throws IllegalStateException if the Spring lifecycle is not running
     */
    public URI uri() {
        return application().uri();
    }

    /** Returns the configured live-view affinity node identifier.
     * @return node identifier */
    public String nodeId() {
        var running = application;
        return running == null ? config.liveViewOwnership().localNodeId() : running.nodeId();
    }

    /**
     * Captures operational state without requiring the lifecycle to be running.
     *
     * <p>A stopped lifecycle reports zero current activity while retaining its
     * configured capacities. This makes health and metrics polling safe during
     * startup and shutdown.</p>
     *
     * @return current operational snapshot
     */
    public RuntimeSnapshot runtimeSnapshot() {
        var running = application;
        if (running != null) {
            return cumulative(running.runtimeSnapshot());
        }
        return new RuntimeSnapshot(
                false,
                completedRequests,
                0,
                0,
                false,
                rejectedRequests,
                0,
                peakActiveRequests,
                config.maxLiveViews(),
                config.maxSessions(),
                config.maxConcurrentRequests()
        );
    }

    @Override
    public String transport() {
        return "jdk";
    }

    private RuntimeSnapshot cumulative(RuntimeSnapshot snapshot) {
        return new RuntimeSnapshot(
                snapshot.running(),
                completedRequests + snapshot.handledRequests(),
                snapshot.liveViews(),
                snapshot.sessions(),
                snapshot.acceptingRequests(),
                rejectedRequests + snapshot.rejectedRequests(),
                snapshot.activeRequests(),
                Math.max(peakActiveRequests, snapshot.peakActiveRequests()),
                snapshot.maxLiveViews(),
                snapshot.maxSessions(),
                snapshot.maxConcurrentRequests()
        );
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "shutdownTimeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Shutdown timeout must be positive");
        }
        return timeout;
    }
}
