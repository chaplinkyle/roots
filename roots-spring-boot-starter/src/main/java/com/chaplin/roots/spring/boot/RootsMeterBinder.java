package com.chaplin.roots.spring.boot;

import com.chaplin.roots.RuntimeSnapshot;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Objects;
import java.util.function.ToDoubleFunction;

final class RootsMeterBinder implements MeterBinder {
    private final RootsRuntime lifecycle;

    RootsMeterBinder(RootsRuntime lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        gauge(registry, "roots.server.running", "Whether the Roots listener is running", null,
                snapshot -> snapshot.running() ? 1 : 0);
        gauge(registry, "roots.server.accepting", "Whether Roots is accepting application requests", null,
                snapshot -> snapshot.acceptingRequests() ? 1 : 0);
        gauge(registry, "roots.requests.active", "Currently executing Roots requests", "requests",
                RuntimeSnapshot::activeRequests);
        gauge(registry, "roots.requests.peak", "Peak concurrently executing Roots requests", "requests",
                RuntimeSnapshot::peakActiveRequests);
        gauge(registry, "roots.requests.capacity", "Configured concurrent Roots request capacity", "requests",
                RuntimeSnapshot::maxConcurrentRequests);
        counter(registry, "roots.requests.handled", "Completed Roots requests", RuntimeSnapshot::handledRequests);
        counter(registry, "roots.requests.rejected", "Admission-control rejected Roots requests",
                RuntimeSnapshot::rejectedRequests);
        gauge(registry, "roots.views.active", "Current Roots live browser views", "views",
                RuntimeSnapshot::liveViews);
        gauge(registry, "roots.views.capacity", "Configured Roots live-view capacity", "views",
                RuntimeSnapshot::maxLiveViews);
        gauge(registry, "roots.sessions.active", "Current Roots sessions", "sessions",
                RuntimeSnapshot::sessions);
        gauge(registry, "roots.sessions.capacity", "Configured Roots session capacity", "sessions",
                RuntimeSnapshot::maxSessions);
    }

    private void gauge(
            MeterRegistry registry,
            String name,
            String description,
            String baseUnit,
            ToDoubleFunction<RuntimeSnapshot> value
    ) {
        var builder = Gauge.builder(name, lifecycle, owner -> value.applyAsDouble(owner.runtimeSnapshot()))
                .description(description)
                .tag("transport", lifecycle.transport());
        if (baseUnit != null) {
            builder.baseUnit(baseUnit);
        }
        builder.register(registry);
    }

    private void counter(
            MeterRegistry registry,
            String name,
            String description,
            ToDoubleFunction<RuntimeSnapshot> value
    ) {
        FunctionCounter.builder(name, lifecycle, owner -> value.applyAsDouble(owner.runtimeSnapshot()))
                .description(description)
                .baseUnit("requests")
                .tag("transport", lifecycle.transport())
                .register(registry);
    }
}
