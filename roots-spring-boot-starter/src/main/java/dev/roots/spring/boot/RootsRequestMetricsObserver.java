package dev.roots.spring.boot;

import dev.roots.RequestObservation;
import dev.roots.RequestObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class RootsRequestMetricsObserver implements RequestObserver {
    private static final Set<String> STANDARD_METHODS = Set.of(
            "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"
    );

    private final MeterRegistry registry;
    private final String transport;

    RootsRequestMetricsObserver(MeterRegistry registry, RootsTransport transport) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transport = Objects.requireNonNull(transport, "transport").name().toLowerCase(Locale.ROOT);
    }

    @Override
    public void onComplete(RequestObservation observation) {
        Objects.requireNonNull(observation, "observation");
        Timer.builder("roots.http.server.requests")
                .description("Completed Roots HTTP request duration")
                .tag("transport", transport)
                .tag("method", methodTag(observation.method()))
                .tag("status", Integer.toString(observation.status()))
                .tag("outcome", observation.outcome().name().toLowerCase(Locale.ROOT))
                .register(registry)
                .record(observation.duration());
    }

    private static String methodTag(String method) {
        return STANDARD_METHODS.contains(method) ? method : "OTHER";
    }
}
