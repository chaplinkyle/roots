package dev.roots.spring.boot;

import dev.roots.RootsConfig;
import dev.roots.RuntimeSnapshot;
import dev.roots.servlet.RootsServlet;

import java.util.Objects;

final class RootsServletRuntime implements RootsRuntime {
    private final RootsServlet servlet;
    private final RootsConfig config;

    RootsServletRuntime(RootsServlet servlet, RootsConfig config) {
        this.servlet = Objects.requireNonNull(servlet, "servlet");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public RuntimeSnapshot runtimeSnapshot() {
        try {
            return servlet.runtimeSnapshot();
        } catch (IllegalStateException ignored) {
            return new RuntimeSnapshot(
                    false,
                    0,
                    0,
                    0,
                    false,
                    0,
                    0,
                    0,
                    config.maxLiveViews(),
                    config.maxSessions(),
                    config.maxConcurrentRequests()
            );
        }
    }

    @Override
    public String transport() {
        return "servlet";
    }

    @Override
    public String nodeId() {
        return config.liveViewOwnership().localNodeId();
    }
}
