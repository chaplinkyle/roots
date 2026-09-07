package com.chaplin.roots.spring.boot;

import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.spring.boot.testapp.GreetingService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.CookieManager;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootsObservabilityAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RootsAutoConfiguration.class,
                    RootsHealthAutoConfiguration.class,
                    RootsMetricsAutoConfiguration.class
            ))
            .withUserConfiguration(EnabledApplication.class)
            .withPropertyValues("roots.port=0", "roots.shutdown-timeout=1s");

    @Test
    void reportsHealthAcrossRunningDrainingAndStoppedStates() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            var lifecycle = context.getBean(RootsApplicationLifecycle.class);
            var indicator = context.getBean("rootsHealthIndicator", HealthIndicator.class);

            var up = indicator.health();
            assertEquals(Status.UP, up.getStatus());
            assertEquals(true, up.getDetails().get("running"));
            assertEquals(lifecycle.nodeId(), up.getDetails().get("node"));
            assertEquals(true, up.getDetails().get("acceptingRequests"));
            assertEquals(Map.of("requests", 20_000, "liveViews", 10_000, "sessions", 100_000),
                    up.getDetails().get("capacity"));

            lifecycle.application().beginDrain();
            var draining = indicator.health();
            assertEquals(Status.OUT_OF_SERVICE, draining.getStatus());
            assertEquals(false, draining.getDetails().get("acceptingRequests"));

            lifecycle.stop();
            var down = indicator.health();
            assertEquals(Status.DOWN, down.getStatus());
            assertEquals(false, down.getDetails().get("running"));
            assertEquals(Map.of("requests", 20_000, "liveViews", 10_000, "sessions", 100_000),
                    down.getDetails().get("capacity"));
        });
    }

    @Test
    void publishesLiveMicrometerCountersGaugesDescriptionsUnitsAndTags() {
        new ApplicationContextRunner()
                .withUserConfiguration(AutoDiscoveryApplication.class)
                .withPropertyValues("roots.port=0", "roots.shutdown-timeout=1s")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var lifecycle = context.getBean(RootsApplicationLifecycle.class);
                    var registry = context.getBean(MeterRegistry.class);

                    assertEquals(1, gauge(registry, "roots.server.running"));
                    assertEquals(1, gauge(registry, "roots.server.accepting"));
                    assertEquals(20_000, gauge(registry, "roots.requests.capacity"));
                    assertEquals(10_000, gauge(registry, "roots.views.capacity"));
                    assertEquals(100_000, gauge(registry, "roots.sessions.capacity"));
                    assertEquals("Configured concurrent Roots request capacity",
                            registry.get("roots.requests.capacity").gauge().getId().getDescription());
                    assertEquals("requests", registry.get("roots.requests.capacity").gauge().getId().getBaseUnit());

                    try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
                        assertEquals(200, get(client, lifecycle.uri().resolve("/")).statusCode());
                        assertEquals(200, get(client, lifecycle.uri().resolve("/api/greeting")).statusCode());
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }

                    assertTrue(counter(registry, "roots.requests.handled") >= 2);
                    assertEquals(1, gauge(registry, "roots.views.active"));
                    assertEquals(1, gauge(registry, "roots.sessions.active"));
                    assertEquals("jdk", registry.get("roots.views.active").gauge().getId().getTag("transport"));
                    // The client can receive the body before the completion observer records its timer.
                    var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (true) {
                        var completed = registry.find("roots.http.server.requests")
                                .tags("transport", "jdk", "method", "GET", "status", "200", "outcome", "success")
                                .timer();
                        if (completed != null && completed.count() >= 2) {
                            break;
                        }
                        assertTrue(System.nanoTime() < deadline, "Request metrics did not record both responses");
                        Thread.sleep(10);
                    }
                    var requests = registry.get("roots.http.server.requests")
                            .tags("transport", "jdk", "method", "GET", "status", "200", "outcome", "success")
                            .timer();
                    assertEquals(2, requests.count());
                    assertEquals("Completed Roots HTTP request duration", requests.getId().getDescription());
                    assertNull(requests.getId().getTag("path"));
                    assertEquals(12, registry.getMeters().stream()
                            .filter(meter -> meter.getId().getName().startsWith("roots."))
                            .count());

                    lifecycle.application().beginDrain();
                    assertEquals(0, gauge(registry, "roots.server.accepting"));
                });
    }

    @Test
    void observabilityBridgesCanBeDisabledIndependently() {
        contextRunner.withPropertyValues("management.health.roots.enabled=false").run(context -> {
            assertFalse(context.containsBean("rootsHealthIndicator"));
            assertTrue(context.containsBean("rootsMeterBinder"));
        });
        contextRunner.withPropertyValues("management.metrics.enable.roots=false").run(context -> {
            assertTrue(context.containsBean("rootsHealthIndicator"));
            assertFalse(context.containsBean("rootsMeterBinder"));
            assertFalse(context.containsBean("rootsRequestMetricsObserver"));
        });
    }

    @Test
    void backsOffForApplicationOwnedContributorAndBinder() {
        contextRunner.withUserConfiguration(CustomObservability.class).run(context -> {
            assertSame(CustomObservability.HEALTH, context.getBean("rootsHealthIndicator"));
            assertSame(CustomObservability.METRICS, context.getBean("rootsMeterBinder"));
        });
    }

    @Test
    void startsNormallyWhenActuatorAndMicrometerAreAbsent() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(
                        "org.springframework.boot.health.contributor",
                        "io.micrometer.core.instrument"
                ))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBean(RootsApplicationLifecycle.class).isRunning());
                    assertFalse(context.containsBean("rootsHealthIndicator"));
                    assertFalse(context.containsBean("rootsMeterBinder"));
                    assertFalse(context.containsBean("rootsRequestMetricsObserver"));
                });
    }

    @Test
    void bootOwnsAndClosesTheMetricsRegistry() {
        var captured = new AtomicReference<MeterRegistry>();
        new ApplicationContextRunner()
                .withUserConfiguration(AutoDiscoveryApplication.class)
                .withPropertyValues("roots.port=0", "roots.shutdown-timeout=1s")
                .run(context -> {
                    var registry = context.getBean(MeterRegistry.class);
                    captured.set(registry);
                    assertFalse(registry.isClosed());
                    assertNotNull(registry.find("roots.server.running").gauge());
                });
        assertTrue(captured.get().isClosed());
    }

    @Test
    void requestCountersRemainMonotonicAcrossLifecycleRestart() {
        new ApplicationContextRunner()
                .withUserConfiguration(AutoDiscoveryApplication.class)
                .withPropertyValues("roots.port=0", "roots.shutdown-timeout=1s")
                .run(context -> {
                    var lifecycle = context.getBean(RootsApplicationLifecycle.class);
                    var registry = context.getBean(MeterRegistry.class);
                    assertEquals(200, getUnchecked(lifecycle.uri().resolve("/api/greeting")).statusCode());
                    var beforeRestart = awaitCounterGreaterThan(registry, "roots.requests.handled", 0);

                    lifecycle.stop();
                    var whileStopped = counter(registry, "roots.requests.handled");
                    lifecycle.start();
                    assertEquals(200, getUnchecked(lifecycle.uri().resolve("/api/greeting")).statusCode());
                    var afterRestart = awaitCounterGreaterThan(
                            registry, "roots.requests.handled", whileStopped
                    );

                    assertTrue(beforeRestart >= 1);
                    assertTrue(whileStopped >= beforeRestart);
                    assertTrue(afterRestart > whileStopped);
                });
    }

    private static double gauge(MeterRegistry registry, String name) {
        return registry.get(name).tag("transport", "jdk").gauge().value();
    }

    private static double counter(MeterRegistry registry, String name) {
        return registry.get(name).tag("transport", "jdk").functionCounter().count();
    }

    private static double awaitCounterGreaterThan(MeterRegistry registry, String name, double previous) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        double value;
        do {
            value = counter(registry, name);
            if (value > previous) {
                return value;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for request metrics", exception);
            }
        } while (System.nanoTime() < deadline);
        return counter(registry, name);
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static HttpResponse<String> getUnchecked(URI uri) {
        try (var client = HttpClient.newHttpClient()) {
            return get(client, uri);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRoots(com.chaplin.roots.spring.boot.testapp.Application.class)
    static class EnabledApplication {
        @Bean
        GreetingService greetingService() {
            return new GreetingService();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableRoots(com.chaplin.roots.spring.boot.testapp.Application.class)
    static class AutoDiscoveryApplication {
        @Bean
        GreetingService greetingService() {
            return new GreetingService();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomObservability {
        static final HealthIndicator HEALTH = () -> Health.up().withDetail("owner", "application").build();
        static final MeterBinder METRICS = registry -> {
        };

        @Bean("rootsHealthIndicator")
        HealthIndicator customRootsHealthIndicator() {
            return HEALTH;
        }

        @Bean("rootsMeterBinder")
        MeterBinder customRootsMeterBinder() {
            return METRICS;
        }
    }
}
