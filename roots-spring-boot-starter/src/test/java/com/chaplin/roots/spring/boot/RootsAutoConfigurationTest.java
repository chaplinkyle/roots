package com.chaplin.roots.spring.boot;

import com.chaplin.roots.AuthenticationProvider;
import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.RequestObservation;
import com.chaplin.roots.RequestObserver;
import com.chaplin.roots.SessionRepository;
import com.chaplin.roots.LiveViewOwnership;
import com.chaplin.roots.ProxyPolicy;
import com.chaplin.roots.RateLimiter;
import com.chaplin.roots.RateLimitDecision;
import com.chaplin.roots.spring.boot.testapp.GreetingService;
import com.chaplin.roots.spring.boot.testapp.api.greeting.Route;
import com.chaplin.roots.spring.boot.testapp.pages.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootsAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RootsAutoConfiguration.class));

    @BeforeEach
    void resetLifecycleCounts() {
        Page.resetDestroyed();
        Route.resetDestroyed();
        ObservedApplication.OBSERVATION.set(null);
    }

    @Test
    void startsConfiguredServerAndInjectsSpringServices() {
        contextRunner
                .withUserConfiguration(EnabledApplication.class)
                .withPropertyValues(
                        "roots.port=0",
                        "roots.pages-package=com.chaplin.roots.spring.boot.testapp.pages",
                        "roots.api-package=com.chaplin.roots.spring.boot.testapp.api",
                        "roots.development=false",
                        "roots.view-timeout=17m",
                        "roots.session-timeout=3h",
                        "roots.shutdown-timeout=1s",
                        "roots.max-request-bytes=4096",
                        "roots.request-body-memory-threshold=1024",
                        "roots.max-multipart-text-field-bytes=2048",
                        "roots.max-concurrent-requests=44",
                        "roots.max-live-views=22",
                        "roots.max-sessions=33",
                        "roots.secure-cookies=true",
                        "roots.content-security-policy=default-src 'none'"
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var config = context.getBean(RootsConfig.class);
                    var lifecycle = context.getBean(RootsApplicationLifecycle.class);

                    assertTrue(lifecycle.isRunning());
                    assertEquals("127.0.0.1", config.host());
                    assertEquals(0, config.port());
                    assertEquals("com.chaplin.roots.spring.boot.testapp.pages", config.pagesPackage());
                    assertEquals("com.chaplin.roots.spring.boot.testapp.api", config.apiPackage());
                    assertFalse(config.development());
                    assertEquals(Duration.ofMinutes(17), config.viewTimeout());
                    assertEquals(Duration.ofHours(3), config.sessionTimeout());
                    assertEquals(4096, config.maxRequestBytes());
                    assertEquals(1024, config.requestBodyPolicy().memoryThreshold());
                    assertEquals(2048, config.requestBodyPolicy().maxMultipartTextFieldBytes());
                    assertEquals(44, config.maxConcurrentRequests());
                    assertEquals(22, config.maxLiveViews());
                    assertEquals(33, config.maxSessions());
                    assertTrue(config.secureCookies());
                    assertEquals("default-src 'none'", config.contentSecurityPolicy());
                    assertSame(context.getBean(SessionRepository.class), config.sessionRepository());
                    assertSame(context.getBean(LiveViewOwnership.class), config.liveViewOwnership());
                    assertSame(context.getBean(AuthenticationProvider.class), config.authenticationProvider());

                    try (var client = HttpClient.newHttpClient()) {
                        var page = get(client, lifecycle.uri().resolve("/"));
                        var route = get(client, lifecycle.uri().resolve("/api/greeting"));
                        assertEquals(200, page.statusCode());
                        assertTrue(page.body().contains("Hello, Boot page!"));
                        assertEquals("default-src 'none'", page.headers()
                                .firstValue("Content-Security-Policy").orElseThrow());
                        assertEquals(200, route.statusCode());
                        assertEquals("Hello, Boot route!", route.body());
                        assertEquals(1, Route.destroyed());
                        assertEquals(0, Page.destroyed());
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                });

        assertEquals(1, Page.destroyed());
    }

    @Test
    void doesNothingWithoutEnableAnnotation() {
        contextRunner.withUserConfiguration(ServiceOnlyConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("rootsApplicationDescriptor"));
            assertEquals(0, context.getBeansOfType(RootsConfig.class).size());
            assertEquals(0, context.getBeansOfType(RootsApplicationLifecycle.class).size());
        });
    }

    @Test
    void canBeDisabledWithAProperty() {
        contextRunner
                .withUserConfiguration(EnabledApplication.class)
                .withPropertyValues("roots.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("rootsApplicationDescriptor"));
                    assertEquals(0, context.getBeansOfType(RootsConfig.class).size());
                    assertEquals(0, context.getBeansOfType(RootsApplicationLifecycle.class).size());
                });
    }

    @Test
    void appliesCustomizersAfterBoundProperties() {
        contextRunner
                .withUserConfiguration(CustomizedApplication.class)
                .withPropertyValues("roots.port=0", "roots.development=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var config = context.getBean(RootsConfig.class);
                    assertTrue(config.development());
                    assertEquals(1, config.middleware().size());
                });
    }

    @Test
    void discoversApplicationOwnedRequestObservers() {
        contextRunner
                .withUserConfiguration(ObservedApplication.class)
                .withPropertyValues("roots.port=0", "roots.development=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var config = context.getBean(RootsConfig.class);
                    var lifecycle = context.getBean(RootsApplicationLifecycle.class);
                    assertEquals(1, config.requestObservers().size());

                    var response = getUnchecked(lifecycle.uri().resolve("/api/greeting"));
                    var observation = awaitObservation(ObservedApplication.OBSERVATION);
                    assertEquals(200, response.statusCode());
                    assertNotNull(observation);
                    assertEquals("/api/greeting", observation.path());
                    assertEquals(response.headers().firstValue("traceparent").orElseThrow(),
                            observation.traceContext().traceparent());
                });
    }

    @Test
    void backsOffForAnApplicationOwnedConfiguration() {
        contextRunner
                .withUserConfiguration(CustomConfigApplication.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertSame(CustomConfigApplication.CONFIG, context.getBean(RootsConfig.class));
                    assertTrue(context.getBean(RootsApplicationLifecycle.class).isRunning());
                });
    }

    @Test
    void reportsInvalidConfigurationAsContextStartupFailure() {
        contextRunner
                .withUserConfiguration(EnabledApplication.class)
                .withPropertyValues(
                        "roots.port=0",
                        "roots.max-concurrent-requests=10",
                        "roots.max-live-views=10"
                )
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertNotNull(context.getStartupFailure());
                });
    }

    @Test
    void rejectsUnsafeServletMappingsDuringPropertyBinding() {
        for (var invalid : new String[] {"relative", "/roots/", "/roots/*", "/../roots", "//roots"}) {
            contextRunner
                    .withUserConfiguration(EnabledApplication.class)
                    .withPropertyValues("roots.port=0", "roots.servlet-path=" + invalid)
                    .run(context -> assertNotNull(context.getStartupFailure(), invalid));
        }
    }

    @Test
    void publishesServletPathConfigurationMetadata() throws Exception {
        try (var input = RootsAutoConfigurationTest.class.getClassLoader()
                .getResourceAsStream("META-INF/spring-configuration-metadata.json")) {
            assertNotNull(input);
            var metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var propertyIndex = metadata.indexOf("\"name\": \"roots.servlet-path\"");
            assertTrue(propertyIndex >= 0, metadata);
            var propertyMetadata = metadata.substring(propertyIndex, Math.min(metadata.length(), propertyIndex + 300));
            assertTrue(propertyMetadata.contains("\"defaultValue\": \"\\/\""), metadata);
            assertTrue(metadata.contains("\"name\": \"roots.trusted-proxies\""), metadata);
            assertTrue(metadata.contains("\"name\": \"roots.rate-limit-requests\""), metadata);
            assertTrue(metadata.contains("\"name\": \"roots.rate-limit-window\""), metadata);
            assertTrue(metadata.contains("\"name\": \"roots.max-rate-limit-clients\""), metadata);
        }
    }

    @Test
    void configuresTrustedProxyResolutionAndPreSessionRateLimiting() {
        contextRunner
                .withUserConfiguration(EnabledApplication.class)
                .withPropertyValues(
                        "roots.port=0",
                        "roots.development=false",
                        "roots.trusted-proxies[0]=127.0.0.0/8",
                        "roots.rate-limit-requests=1",
                        "roots.rate-limit-window=1m",
                        "roots.max-rate-limit-clients=10"
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var lifecycle = context.getBean(RootsApplicationLifecycle.class);
                    try (var client = HttpClient.newHttpClient()) {
                        var request = HttpRequest.newBuilder(lifecycle.uri().resolve("/api/greeting"))
                                .header("Forwarded", "for=203.0.113.81;proto=https;host=app.example")
                                .GET().build();
                        var first = client.send(request, HttpResponse.BodyHandlers.ofString());
                        var second = client.send(request, HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, first.statusCode());
                        assertEquals("1", first.headers().firstValue("RateLimit-Limit").orElseThrow());
                        assertEquals("0", first.headers().firstValue("RateLimit-Remaining").orElseThrow());
                        assertEquals(429, second.statusCode());
                        assertEquals("60", second.headers().firstValue("Retry-After").orElseThrow());
                        assertEquals(1, lifecycle.runtimeSnapshot().sessions());
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                });
    }

    @Test
    void discoversApplicationOwnedProxyAndRateLimiterBeans() {
        contextRunner
                .withUserConfiguration(NetworkPolicyApplication.class)
                .withPropertyValues("roots.port=0")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var config = context.getBean(RootsConfig.class);
                    assertSame(context.getBean(ProxyPolicy.class), config.proxyPolicy());
                    assertSame(context.getBean(RateLimiter.class), config.rateLimiter());
                });
    }

    @Test
    void rejectsInvalidProxyAndRateLimitProperties() {
        for (var property : new String[] {
                "roots.trusted-proxies[0]=localhost/32",
                "roots.trusted-proxies[0]=10.0.0.0/99",
                "roots.rate-limit-requests=-1",
                "roots.rate-limit-window=0s",
                "roots.max-rate-limit-clients=0"
        }) {
            contextRunner
                    .withUserConfiguration(EnabledApplication.class)
                    .withPropertyValues("roots.port=0", property)
                    .run(context -> assertNotNull(context.getStartupFailure(), property));
        }
    }

    @Test
    void lifecycleSupportsExplicitStopCallbackAndRestart() {
        contextRunner
                .withUserConfiguration(EnabledApplication.class)
                .withPropertyValues("roots.port=0", "roots.shutdown-timeout=1s")
                .run(context -> {
                    var lifecycle = context.getBean(RootsApplicationLifecycle.class);
                    assertNotNull(lifecycle.uri());
                    var callback = new AtomicBoolean();

                    lifecycle.stop(() -> callback.set(true));

                    assertTrue(callback.get());
                    assertFalse(lifecycle.isRunning());
                    assertThrows(IllegalStateException.class, lifecycle::application);
                    lifecycle.stop();

                    lifecycle.start();
                    assertTrue(lifecycle.isRunning());
                    assertNotNull(lifecycle.uri());
                });
    }

    @Test
    void discoversAutoConfigurationFromStarterMetadata() {
        new ApplicationContextRunner()
                .withUserConfiguration(AutoDiscoveryApplication.class)
                .withPropertyValues("roots.port=0", "roots.shutdown-timeout=1s")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBean(RootsApplicationLifecycle.class).isRunning());
                    assertEquals(200, getUnchecked(
                            context.getBean(RootsApplicationLifecycle.class).uri().resolve("/api/greeting")
                    ).statusCode());
                });
    }

    @Test
    void rejectsNonPositiveShutdownTimeout() {
        var config = RootsConfig.forApplication(com.chaplin.roots.spring.boot.testapp.Application.class)
                .port(0)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> new RootsApplicationLifecycle(config, Duration.ZERO));
        assertThrows(NullPointerException.class,
                () -> new RootsApplicationLifecycle(config, null));
    }

    @Test
    void defaultJdkTransportDoesNotRequireServletClasses() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RootsAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("jakarta.servlet", "com.chaplin.roots.servlet"))
                .withUserConfiguration(EnabledApplication.class)
                .withPropertyValues("roots.port=0", "roots.shutdown-timeout=1s")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBean(RootsApplicationLifecycle.class).isRunning());
                });
    }

    @Test
    void servletTransportFailsFastOutsideAServletApplication() {
        contextRunner
                .withUserConfiguration(EnabledApplication.class)
                .withPropertyValues("roots.transport=servlet")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(context.getStartupFailure().getMessage().contains(
                            "requires a Jakarta Servlet web application"));
                });
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

    private static RequestObservation awaitObservation(AtomicReference<RequestObservation> reference) {
        var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (reference.get() == null && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
        return reference.get();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRoots(com.chaplin.roots.spring.boot.testapp.Application.class)
    static class EnabledApplication {
        @Bean
        GreetingService greetingService() {
            return new GreetingService();
        }

        @Bean
        SessionRepository sessionRepository() {
            return SessionRepository.inMemory();
        }

        @Bean
        LiveViewOwnership liveViewOwnership() {
            return LiveViewOwnership.inMemory("boot-test-node");
        }

        @Bean
        AuthenticationProvider authenticationProvider() {
            return AuthenticationProvider.transportIdentity();
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
    static class ServiceOnlyConfiguration {
        @Bean
        GreetingService greetingService() {
            return new GreetingService();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRoots(com.chaplin.roots.spring.boot.testapp.Application.class)
    static class CustomizedApplication {
        @Bean
        GreetingService greetingService() {
            return new GreetingService();
        }

        @Bean
        RootsConfigCustomizer rootsConfigCustomizer() {
            return builder -> builder
                    .development(true)
                    .use((request, chain) -> chain.next().withHeader("X-Roots-Customized", "true"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRoots(com.chaplin.roots.spring.boot.testapp.Application.class)
    static class ObservedApplication {
        static final AtomicReference<RequestObservation> OBSERVATION = new AtomicReference<>();

        @Bean
        GreetingService greetingService() {
            return new GreetingService();
        }

        @Bean
        @Order(1)
        RequestObserver requestObserver() {
            return OBSERVATION::set;
        }

        @Bean
        @Order(0)
        RequestObserver failingRequestObserver() {
            return ignored -> {
                throw new IllegalStateException("expected observer failure");
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRoots(com.chaplin.roots.spring.boot.testapp.Application.class)
    static class NetworkPolicyApplication {
        @Bean
        GreetingService greetingService() {
            return new GreetingService();
        }

        @Bean
        ProxyPolicy proxyPolicy() {
            return ProxyPolicy.directOnly();
        }

        @Bean
        RateLimiter rateLimiter() {
            return ignored -> RateLimitDecision.unlimited();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRoots(com.chaplin.roots.spring.boot.testapp.Application.class)
    static class CustomConfigApplication {
        static final RootsConfig CONFIG = RootsConfig.forApplication(
                        com.chaplin.roots.spring.boot.testapp.Application.class)
                .port(0)
                .build();

        @Bean
        RootsConfig rootsConfig() {
            return CONFIG;
        }
    }
}
