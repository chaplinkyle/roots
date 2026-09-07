package com.chaplin.roots.spring;

import com.chaplin.roots.Roots;
import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.InstanceFactory;
import com.chaplin.roots.spring.testapp.Application;
import com.chaplin.roots.spring.testapp.GreetingService;
import com.chaplin.roots.spring.testapp.api.greeting.Route;
import com.chaplin.roots.spring.testapp.pages.Page;
import com.chaplin.roots.spring.testapp.pages.Layout;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringServerIntegrationTest {
    @Test
    void injectsSpringServicesIntoPagesAndApiRoutes() throws Exception {
        Page.resetDestroyed();
        Layout.resetDestroyed();
        Route.resetDestroyed();
        com.chaplin.roots.spring.testapp.api.failure.Route.resetDestroyed();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(GreetingService.class);
            context.refresh();
            var config = RootsConfig.forApplication(Application.class)
                    .port(0)
                    .instanceFactory(new SpringInstanceFactory(context))
                    .build();

            try (var application = Roots.start(config);
                 var client = HttpClient.newHttpClient()) {
                var page = get(client, application.uri().resolve("/"));
                var route = get(client, application.uri().resolve("/api/greeting"));
                var options = client.send(HttpRequest.newBuilder(application.uri().resolve("/api/greeting"))
                                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                var failure = get(client, application.uri().resolve("/api/failure"));

                assertEquals(200, page.statusCode());
                assertTrue(page.body().contains("Hello, Spring page!"));
                assertEquals(200, route.statusCode());
                assertEquals("Hello, Spring route!", route.body());
                assertEquals(204, options.statusCode());
                assertEquals("GET, HEAD, OPTIONS", options.headers().firstValue("Allow").orElseThrow());
                assertEquals(2, Route.destroyed());
                assertEquals(500, failure.statusCode());
                assertEquals(1, com.chaplin.roots.spring.testapp.api.failure.Route.destroyed());
                assertEquals(0, Page.destroyed());
                assertEquals(0, Layout.destroyed());
                // close() has no grace period; wait for lifecycle cleanup before asserting destruction.
                application.closeGracefully(Duration.ofSeconds(5));
            }
            assertEquals(1, Page.destroyed());
            assertEquals(1, Layout.destroyed());
        }
    }

    @Test
    void destroysAPageWhenLaterLayoutConstructionFails() throws Exception {
        Page.resetDestroyed();
        Layout.resetDestroyed();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(GreetingService.class);
            context.refresh();
            var spring = new SpringInstanceFactory(context);
            InstanceFactory failing = new InstanceFactory() {
                @Override
                public Object create(Class<?> type) throws Exception {
                    if (type == Layout.class) {
                        throw new IllegalStateException("expected layout construction failure");
                    }
                    return spring.create(type);
                }

                @Override
                public void destroy(Object instance) {
                    spring.destroy(instance);
                }
            };
            var config = RootsConfig.forApplication(Application.class)
                    .port(0)
                    .instanceFactory(failing)
                    .build();

            try (var application = Roots.start(config);
                 var client = HttpClient.newHttpClient()) {
                assertEquals(500, get(client, application.uri().resolve("/")).statusCode());
                assertEquals(1, Page.destroyed());
                assertEquals(0, Layout.destroyed());
            }
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }
}
