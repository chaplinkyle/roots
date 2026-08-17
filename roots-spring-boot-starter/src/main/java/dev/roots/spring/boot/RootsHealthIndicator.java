package dev.roots.spring.boot;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.Map;
import java.util.Objects;

final class RootsHealthIndicator implements HealthIndicator {
    private final RootsRuntime lifecycle;

    RootsHealthIndicator(RootsRuntime lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public Health health() {
        var snapshot = lifecycle.runtimeSnapshot();
        var builder = !snapshot.running()
                ? Health.down()
                : snapshot.acceptingRequests() ? Health.up() : Health.outOfService();
        return builder
                .withDetail("running", snapshot.running())
                .withDetail("node", lifecycle.nodeId())
                .withDetail("acceptingRequests", snapshot.acceptingRequests())
                .withDetail("handledRequests", snapshot.handledRequests())
                .withDetail("rejectedRequests", snapshot.rejectedRequests())
                .withDetail("activeRequests", snapshot.activeRequests())
                .withDetail("peakActiveRequests", snapshot.peakActiveRequests())
                .withDetail("liveViews", snapshot.liveViews())
                .withDetail("sessions", snapshot.sessions())
                .withDetail("capacity", Map.of(
                        "requests", snapshot.maxConcurrentRequests(),
                        "liveViews", snapshot.maxLiveViews(),
                        "sessions", snapshot.maxSessions()
                ))
                .build();
    }
}
