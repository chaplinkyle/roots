package com.chaplin.roots.spring.boot;

import com.chaplin.roots.Roots;
import com.chaplin.roots.servlet.RootsServlet;
import com.chaplin.roots.spring.boot.testapp.GreetingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.micrometer.core.instrument.MeterRegistry;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RootsServletBootIntegrationTest {
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");
    private static final Pattern ACTION = Pattern.compile("data-roots-on-click=\"([^\"]+)\"");

    @Test
    void drainsLiveStreamsBeforeTheContainersGracefulShutdownWait() throws Exception {
        var context = new SpringApplicationBuilder(ServletApplication.class).web(WebApplicationType.SERVLET)
                .properties("server.port=0", "server.shutdown=graceful", "spring.lifecycle.timeout-per-shutdown-phase=10s",
                        "roots.transport=servlet", "roots.development=false", "roots.shutdown-timeout=1s",
                        "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration,org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
                        "spring.main.banner-mode=off", "logging.level.root=OFF").run();
        var runtime = context.getBean(RootsRuntime.class);
        var base = URI.create("http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort() + "/");
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var html = get(client, base).body();
            var response = client.send(HttpRequest.newBuilder(base.resolve("_roots/stream?view=" + encode(match(VIEW, html))
                            + "&csrf=" + encode(match(CSRF, html)) + "&protocol=" + encode(Roots.PROTOCOL_VERSION)))
                    .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                assertEquals(200, response.statusCode());
                assertEquals(": connected", reader.readLine());
                var started = System.nanoTime();
                context.close();
                assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis() < 5000,
                        "Roots must close SSE before Boot's 10-second graceful timeout");
                assertFalse(runtime.runtimeSnapshot().running());
                assertEquals(0, runtime.runtimeSnapshot().activeRequests());
            }
        } finally { context.close(); }
    }

    @Test
    void runsRootsInsideBootsExistingServletContainer() throws Exception {
        RootsRuntime runtime;
        try (var context = new SpringApplicationBuilder(ServletApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.port=0",
                        "server.servlet.context-path=/company",
                        "roots.transport=servlet",
                        "roots.servlet-path=/roots",
                        "roots.development=false",
                        "roots.shutdown-timeout=1s",
                        "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration,org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
                        "spring.main.banner-mode=off",
                        "logging.level.root=OFF"
                )
                .run()) {
            var web = (ServletWebServerApplicationContext) context;
            var contextBase = URI.create("http://127.0.0.1:" + web.getWebServer().getPort() + "/company/");
            var base = contextBase.resolve("roots/");
            var registration = context.getBean("rootsServletRegistration", ServletRegistrationBean.class);
            runtime = context.getBean(RootsRuntime.class);

            assertEquals(java.util.Set.of("/roots/*"), registration.getUrlMappings());
            assertTrue(registration.isAsyncSupported());
            assertEquals("servlet", runtime.transport());
            assertTrue(runtime.runtimeSnapshot().running());
            assertFalse(context.containsBean("rootsApplicationLifecycle"));
            assertTrue(context.getBean(RootsServlet.class).runtimeSnapshot().acceptingRequests());
            assertEquals(Status.UP, context.getBean("rootsHealthIndicator", HealthIndicator.class)
                    .health().getStatus());
            assertEquals("servlet", context.getBean(MeterRegistry.class)
                    .get("roots.server.running").gauge().getId().getTag("transport"));

            try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
                var health = get(client, base.resolve("_roots/health"));
                var page = get(client, base);
                var exactMount = get(client, contextBase.resolve("roots"));
                var api = get(client, base.resolve("api/greeting"));
                var runtimeScript = get(client, base.resolve("_roots/client.js"));
                var mvc = get(client, contextBase.resolve("mvc/ping"));
                assertEquals(200, health.statusCode(), health.body());
                assertEquals(200, page.statusCode(), page.body());
                assertEquals(200, exactMount.statusCode(), exactMount.body());
                assertTrue(page.body().contains("Hello, Boot page!"), page.body());
                assertTrue(page.body().contains("src=\"/company/roots/_roots/client.js\""), page.body());
                assertTrue(page.body().contains("href=\"/company/roots/\""), page.body());
                assertTrue(page.body().contains("data-roots-mount-path=\"/company/roots\""), page.body());
                assertTrue(page.headers().firstValue("Set-Cookie").orElseThrow()
                        .contains("Path=/company/roots;"));
                var actionBody = "_view=" + encode(match(VIEW, page.body()))
                        + "&_csrf=" + encode(match(CSRF, page.body()))
                        + "&_protocol=" + encode(Roots.PROTOCOL_VERSION)
                        + "&_action=" + encode(match(ACTION, page.body()))
                        + "&_event=click";
                var action = client.send(HttpRequest.newBuilder(base.resolve("_roots/action"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(actionBody)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, action.statusCode(), action.body());
                assertTrue(action.body().contains("Count 1"), action.body());
                assertEquals(200, api.statusCode(), api.body());
                assertEquals("Hello, Boot route!", api.body());
                assertEquals(200, runtimeScript.statusCode(), runtimeScript.body());
                assertTrue(runtimeScript.body().contains("applicationUrl('/_roots/action')"), runtimeScript.body());
                assertEquals(200, mvc.statusCode(), mvc.body());
                assertEquals("mvc", mvc.body());
            }
        }
        assertFalse(runtime.runtimeSnapshot().running());
        assertTrue(runtime.runtimeSnapshot().handledRequests() >= 3);
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String match(Pattern pattern, String body) {
        var matcher = pattern.matcher(body);
        assertTrue(matcher.find(), body);
        return matcher.group(1);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableRoots(com.chaplin.roots.spring.boot.testapp.Application.class)
    static class ServletApplication {
        @Bean
        GreetingService greetingService() {
            return new GreetingService();
        }

        @Bean
        MvcEndpoint mvcEndpoint() {
            return new MvcEndpoint();
        }
    }

    @RestController
    static class MvcEndpoint {
        @GetMapping("/mvc/ping")
        String ping() {
            return "mvc";
        }
    }
}
