package com.chaplin.roots;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PrerenderIntegrationTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Test
    void generatesStaticAndDynamicClasspathResources() throws Exception {
        com.chaplin.roots.prerenderapp.pages.Page.reset();
        var output = Files.createTempDirectory("roots-prerender-output-");
        var stale = output.resolve(
                "META-INF/roots/prerender/com.chaplin.roots.prerenderapp.Application/obsolete.html");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "obsolete");
        var report = Prerenderer.generate(config(), output);

        assertEquals(output.toAbsolutePath().normalize(), report.outputDirectory());
        assertEquals(Set.of("/", "/products/north%20star", "/products/a%2Bb"),
                report.routes().stream().map(PrerenderedRoute::path).collect(java.util.stream.Collectors.toSet()));
        assertTrue(Files.isRegularFile(output.resolve(
                "META-INF/roots/prerender/com.chaplin.roots.prerenderapp.Application.index")));
        assertFalse(Files.exists(stale));
        for (var route : report.routes()) {
            var html = Files.readString(output.resolve(route.resource()), StandardCharsets.UTF_8);
            assertTrue(html.contains("data-roots-static"), html);
            assertFalse(html.contains("/_roots/client.js"), html);
            assertFalse(html.contains("data-roots-view"), html);
            if (route.path().equals("/")) {
                assertTrue(html.contains("property=\"og:site_name\" data-roots-head=\"og:site_name\" content=\"Roots Fixtures\""), html);
            }
        }
    }

    @Test
    void servesPrerendersWithoutSessionsAndCoalescesIncrementalRegeneration() throws Exception {
        com.chaplin.roots.prerenderapp.pages.Page.reset();
        try (var application = Roots.start(config())) {
            var home = get(application.uri(), "/");
            assertEquals(200, home.statusCode(), home.body());
            assertTrue(home.body().contains("Generation 1"), home.body());
            assertEquals("HIT", home.headers().firstValue("X-Roots-Prerender").orElseThrow());
            assertTrue(home.headers().firstValue("Set-Cookie").isEmpty());
            assertTrue(home.headers().firstValue("Content-Security-Policy").isPresent());
            assertTrue(home.body().contains("<title>Prerender fixture</title>"), home.body());
            assertTrue(home.body().contains("href=\"/static.css\""), home.body());
            assertTrue(home.body().contains("rel=\"canonical\" data-roots-head=\"canonical\" href=\"https://example.com/\""), home.body());
            assertTrue(home.body().contains("name=\"robots\" data-roots-head=\"robots\" content=\"index, follow\""), home.body());
            assertTrue(home.body().contains("property=\"og:title\" data-roots-head=\"og:title\" content=\"Prerender fixture\""), home.body());
            assertTrue(home.body().contains("property=\"og:description\" data-roots-head=\"og:description\" content=\"Static fixture\""), home.body());
            assertTrue(home.body().contains("property=\"og:image:alt\" data-roots-head=\"og:image:alt\" content=\"Static preview\""), home.body());
            assertTrue(home.body().contains("property=\"og:site_name\" data-roots-head=\"og:site_name\" content=\"Roots Fixtures\""), home.body());
            assertFalse(home.body().contains("/_roots/client.js"), home.body());

            var product = get(application.uri(), "/products/north%20star");
            assertEquals(200, product.statusCode(), product.body());
            assertTrue(product.body().contains("Static product north star"), product.body());
            assertTrue(product.headers().firstValue("Set-Cookie").isEmpty());

            var etag = home.headers().firstValue("ETag").orElseThrow();
            var conditional = CLIENT.send(HttpRequest.newBuilder(application.uri())
                    .header("If-None-Match", "W/" + etag)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(304, conditional.statusCode());
            assertEquals("", conditional.body());

            var head = CLIENT.send(HttpRequest.newBuilder(application.uri()).method(
                    "HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, head.statusCode());
            assertEquals("", head.body());
            assertEquals(etag, head.headers().firstValue("ETag").orElseThrow());

            var rejected = CLIENT.send(HttpRequest.newBuilder(application.uri())
                    .POST(HttpRequest.BodyPublishers.ofString("no"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, rejected.statusCode());
            assertTrue(rejected.headers().firstValue("Set-Cookie").isEmpty());

            var live = get(application.uri(), "/live");
            assertEquals(200, live.statusCode(), live.body());
            assertTrue(live.body().contains("data-roots-view"), live.body());
            assertTrue(live.headers().firstValue("Set-Cookie").isPresent());

            Thread.sleep(1_100);
            var requests = java.util.stream.IntStream.range(0, 12)
                    .mapToObj(ignored -> CLIENT.sendAsync(
                            HttpRequest.newBuilder(application.uri()).GET().build(),
                            HttpResponse.BodyHandlers.ofString()
                    ))
                    .toList();
            var responses = CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> requests.stream().map(CompletableFuture::join).toList())
                    .get(5, TimeUnit.SECONDS);
            assertTrue(responses.stream().allMatch(response -> response.statusCode() == 200));
            assertTrue(responses.stream().anyMatch(response -> response.headers()
                    .firstValue("X-Roots-Prerender").orElse("").equals("STALE")));
            await(() -> com.chaplin.roots.prerenderapp.pages.Page.generations() >= 2);
            var regenerated = awaitBody(application.uri(), "Generation 2");
            assertEquals(2, com.chaplin.roots.prerenderapp.pages.Page.generations());
            assertTrue(regenerated.body().contains("Generation 2"), regenerated.body());
            assertEquals("HIT", regenerated.headers().firstValue("X-Roots-Prerender").orElseThrow());
        }
    }

    @Test
    void loadsBuildGeneratedHtmlInsteadOfRenderingAtStartup() throws Exception {
        com.chaplin.roots.prerenderapp.pages.Page.reset();
        var output = Files.createTempDirectory("roots-prerender-classpath-");
        var report = Prerenderer.generate(config(), output);
        var root = report.routes().stream().filter(route -> route.path().equals("/")).findFirst().orElseThrow();
        var resource = output.resolve(root.resource());
        Files.writeString(resource, Files.readString(resource).replace("Generation 1", "Compiled artifact"));

        var testClasses = com.chaplin.roots.prerenderapp.Application.class.getProtectionDomain()
                .getCodeSource().getLocation();
        try (var loader = new FixtureClassLoader(
                new URL[] {output.toUri().toURL(), testClasses},
                PrerenderIntegrationTest.class.getClassLoader()
        )) {
            var anchor = Class.forName("com.chaplin.roots.prerenderapp.Application", true, loader);
            var childConfig = RootsConfig.forApplication(anchor).port(0).development(false).build();
            try (var application = Roots.start(childConfig)) {
                var response = get(application.uri(), "/");
                assertEquals(200, response.statusCode(), response.body());
                assertTrue(response.body().contains("Compiled artifact"), response.body());
            }
        }
    }

    @Test
    void keepsStaleContentAndBacksOffWhenRegenerationFails() throws Exception {
        com.chaplin.roots.prerenderapp.pages.Page.reset();
        try (var application = Roots.start(config())) {
            var original = get(application.uri(), "/");
            assertTrue(original.body().contains("Generation 1"), original.body());

            com.chaplin.roots.prerenderapp.pages.Page.failNext();
            Thread.sleep(1_100);
            var stale = get(application.uri(), "/");
            assertEquals("STALE", stale.headers().firstValue("X-Roots-Prerender").orElseThrow());
            assertTrue(stale.body().contains("Generation 1"), stale.body());
            await(() -> com.chaplin.roots.prerenderapp.pages.Page.generations() == 2);

            for (var index = 0; index < 5; index++) {
                var retained = get(application.uri(), "/");
                assertEquals("HIT", retained.headers().firstValue("X-Roots-Prerender").orElseThrow());
                assertTrue(retained.body().contains("Generation 1"), retained.body());
            }
            Thread.sleep(200);
            assertEquals(2, com.chaplin.roots.prerenderapp.pages.Page.generations());
        }
    }

    @Test
    void rejectsReservedDuplicateAndShadowedDynamicOutput() throws Exception {
        var invalidConfig = RootsConfig.forApplication(com.chaplin.roots.invalidprerenderapp.Application.class)
                .development(false)
                .build();
        for (var mode : com.chaplin.roots.invalidprerenderapp.DynamicPaths.Mode.values()) {
            com.chaplin.roots.invalidprerenderapp.DynamicPaths.mode(mode);
            var failure = assertThrows(IllegalStateException.class,
                    () -> Prerenderer.generate(invalidConfig, Files.createTempDirectory("roots-invalid-")));
            var expected = switch (mode) {
                case RESERVED -> "reserved";
                case DUPLICATE -> "duplicate";
                case SHADOWED -> "shadowed";
            };
            assertTrue(failure.getMessage().contains(expected), failure.getMessage());
        }
    }

    private static RootsConfig config() {
        return RootsConfig.forApplication(com.chaplin.roots.prerenderapp.Application.class)
                .port(0)
                .development(false)
                .build();
    }

    private static HttpResponse<String> get(URI base, String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void await(BooleanSupplier condition) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static HttpResponse<String> awaitBody(URI base, String expected) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        HttpResponse<String> response;
        do {
            response = get(base, "/");
            if (response.body().contains(expected)) {
                return response;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Prerendered response did not contain " + expected + ": " + response.body());
    }

    private static final class FixtureClassLoader extends URLClassLoader {
        private FixtureClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.chaplin.roots.prerenderapp.")) {
                synchronized (getClassLoadingLock(name)) {
                    var loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }
}
