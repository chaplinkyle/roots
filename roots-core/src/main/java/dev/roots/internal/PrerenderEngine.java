package dev.roots.internal;

import dev.roots.AsyncComponent;
import dev.roots.Layout;
import dev.roots.Metadata;
import dev.roots.Page;
import dev.roots.PageContext;
import dev.roots.PrerenderReport;
import dev.roots.PrerenderedRoute;
import dev.roots.RootsConfig;
import dev.roots.Session;
import dev.roots.StaticPathProvider;
import dev.roots.annotation.Prerender;
import dev.roots.html.HtmlRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Internal generation entry point exposed to the public facade and build tooling. */
public final class PrerenderEngine {
    static final String HEADER = "ROOTS_PRERENDER";
    static final String VERSION = "1";
    static final int MAX_ROUTES = 10_000;
    static final int MAX_HTML_BYTES = 10 * 1024 * 1024;

    private PrerenderEngine() {
    }

    /** Generates classpath resources for all opted-in routes.
     * @param config application configuration
     * @param outputDirectory destination classes/resources directory
     * @return generation report
     */
    public static PrerenderReport generate(RootsConfig config, Path outputDirectory) {
        Objects.requireNonNull(config, "config");
        var output = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
        var router = ConventionRouter.discover(config);
        var generated = renderAll(config, router);
        var manifestDirectory = output.resolve("META-INF").resolve("roots").resolve("prerender");
        var applicationDirectory = manifestDirectory.resolve(config.applicationClass().getName());
        try {
            Files.createDirectories(applicationDirectory);
            var rows = new StringBuilder(HEADER).append('\t').append(VERSION).append('\t')
                    .append(config.applicationClass().getName()).append('\n');
            var routes = new ArrayList<PrerenderedRoute>();
            var currentFiles = new java.util.HashSet<Path>();
            for (var page : generated) {
                var resource = "META-INF/roots/prerender/" + config.applicationClass().getName()
                        + "/" + hash(page.path()) + ".html";
                var resourceFile = output.resolve(resource).toAbsolutePath().normalize();
                Files.writeString(resourceFile, page.html(), StandardCharsets.UTF_8);
                currentFiles.add(resourceFile);
                rows.append(page.path()).append('\t')
                        .append(page.revalidateSeconds()).append('\t')
                        .append(resource).append('\n');
                routes.add(new PrerenderedRoute(page.path(), resource, page.revalidateSeconds()));
            }
            Files.createDirectories(manifestDirectory);
            Files.writeString(
                    manifestDirectory.resolve(config.applicationClass().getName() + ".index"),
                    rows,
                    StandardCharsets.UTF_8
            );
            try (var files = Files.list(applicationDirectory)) {
                for (var stale : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".html"))
                        .filter(path -> !currentFiles.contains(path.toAbsolutePath().normalize()))
                        .toList()) {
                    Files.delete(stale);
                }
            }
            return new PrerenderReport(output, routes);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write Roots prerender output to " + output, exception);
        }
    }

    static List<GeneratedPage> renderAll(RootsConfig config, ConventionRouter router) {
        var generated = new LinkedHashMap<String, GeneratedPage>();
        for (var plan : plans(config, router)) {
            var page = render(config, plan.match(), plan.path(), plan.revalidateSeconds());
            generated.put(plan.path(), page);
        }
        return List.copyOf(generated.values());
    }

    static List<RenderPlan> plans(RootsConfig config, ConventionRouter router) {
        var plans = new LinkedHashMap<String, RenderPlan>();
        for (var route : router.pageRoutes()) {
            var annotation = route.type().getAnnotation(Prerender.class);
            if (annotation == null) {
                continue;
            }
            validateRoute(route, annotation);
            for (var path : paths(config, router, route, annotation)) {
                var plan = new RenderPlan(path, new ConventionRouter.PageMatch(
                        route,
                        route.pattern().match(path).orElseThrow()
                ), annotation.revalidateSeconds());
                var previous = plans.putIfAbsent(path, plan);
                if (previous != null) {
                    throw new IllegalStateException("Multiple prerendered pages resolve to " + path);
                }
                if (plans.size() > MAX_ROUTES) {
                    throw new IllegalStateException("Prerender output exceeds " + MAX_ROUTES + " routes");
                }
            }
        }
        return List.copyOf(plans.values());
    }

    static GeneratedPage render(
            RootsConfig config,
            ConventionRouter.PageMatch match,
            String path,
            long revalidateSeconds
    ) {
        Page page = null;
        var layouts = new ArrayList<Layout>();
        Throwable failure = null;
        try {
            page = instantiate(config, match.route().type());
            for (var type : match.route().layouts()) {
                layouts.add(instantiate(config, type));
            }
            var session = new Session("roots-prerender");
            var context = new PageContext(path, match.parameters(), Map.of(), session, config.cache());
            var metadata = MetadataSupport.resolve(page, layouts, context);
            var tree = page.render(context);
            for (var index = layouts.size() - 1; index >= 0; index--) {
                tree = layouts.get(index).render(context, tree);
            }
            var rendered = HtmlRenderer.render(tree, context);
            if (!rendered.actions().isEmpty()) {
                throw invalid(path, "contains server actions");
            }
            if (rendered.components().stream().anyMatch(AsyncComponent.class::isInstance)) {
                throw invalid(path, "contains an AsyncComponent");
            }
            if (rendered.html().contains("data-roots-portal=")) {
                throw invalid(path, "contains a Portal");
            }
            if (!session.snapshot().isEmpty()) {
                throw invalid(path, "mutates session state");
            }
            var html = DocumentRenderer.staticPage(rendered.html(), metadata);
            if (html.getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES) {
                throw invalid(path, "exceeds " + MAX_HTML_BYTES + " generated bytes");
            }
            return new GeneratedPage(path, html, revalidateSeconds);
        } catch (Throwable exception) {
            failure = exception;
            throw exception instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Could not prerender " + path, exception);
        } finally {
            var operationFailed = failure != null;
            for (var index = layouts.size() - 1; index >= 0; index--) {
                failure = destroy(config, layouts.get(index), failure);
            }
            if (page != null) {
                failure = destroy(config, page, failure);
            }
            if (!operationFailed && failure != null) {
                throw new IllegalStateException("Could not destroy prerender instance for " + path, failure);
            }
        }
    }

    private static List<String> paths(
            RootsConfig config,
            ConventionRouter router,
            ConventionRouter.PageRoute route,
            Prerender annotation
    ) {
        var parameterNames = route.pattern().parameterNames();
        if (parameterNames.isEmpty()) {
            if (annotation.paths() != StaticPathProvider.None.class) {
                throw invalid(route.pattern().display(), "declares a path provider for a static route");
            }
            return List.of(route.pattern().display());
        }
        if (annotation.paths() == StaticPathProvider.None.class) {
            throw invalid(route.pattern().display(), "requires a StaticPathProvider for " + parameterNames);
        }
        StaticPathProvider provider = null;
        Throwable failure = null;
        try {
            provider = instantiate(config, annotation.paths());
            var supplied = Objects.requireNonNull(provider.paths(), "StaticPathProvider.paths result");
            if (supplied.isEmpty()) {
                throw invalid(route.pattern().display(), "path provider returned no paths");
            }
            var result = new ArrayList<String>();
            for (var parameters : supplied) {
                var copied = Map.copyOf(Objects.requireNonNull(parameters, "static path parameters"));
                var path = route.pattern().expand(copied);
                if (path.equals("/_roots") || path.startsWith("/_roots/")) {
                    throw invalid(path, "uses the reserved /_roots framework path");
                }
                var selected = router.page(path).orElseThrow(() -> invalid(path, "does not match a page route"));
                if (selected.route().type() != route.type()) {
                    throw invalid(path, "is shadowed by " + selected.route().type().getName());
                }
                result.add(path);
            }
            if (result.size() != result.stream().distinct().count()) {
                throw invalid(route.pattern().display(), "path provider returned duplicate paths");
            }
            return List.copyOf(result);
        } catch (Throwable exception) {
            failure = exception;
            throw exception instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Could not enumerate " + route.pattern().display(), exception);
        } finally {
            if (provider != null) {
                var destroyFailure = destroy(config, provider, failure);
                if (failure == null && destroyFailure != null) {
                    throw new IllegalStateException(
                            "Could not destroy static path provider " + annotation.paths().getName(),
                            destroyFailure
                    );
                }
            }
        }
    }

    static void validateRoute(ConventionRouter.PageRoute route, Prerender annotation) {
        if (annotation.revalidateSeconds() < 0) {
            throw invalid(route.pattern().display(), "has a negative revalidation interval");
        }
        if (!route.authorizationPolicies().isEmpty()) {
            throw invalid(route.pattern().display(), "has authorization policies");
        }
    }

    private static <T> T instantiate(RootsConfig config, Class<? extends T> type) {
        try {
            return config.instanceFactory().instantiate(type);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create prerender instance " + type.getName(), exception);
        }
    }

    private static Throwable destroy(RootsConfig config, Object instance, Throwable prior) {
        try {
            config.instanceFactory().destroy(instance);
            return prior;
        } catch (Throwable exception) {
            if (prior != null) {
                prior.addSuppressed(exception);
                return prior;
            }
            return exception;
        }
    }

    private static IllegalStateException invalid(String path, String reason) {
        return new IllegalStateException("Cannot prerender " + path + ": " + reason);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record GeneratedPage(String path, String html, long revalidateSeconds) {
    }

    record RenderPlan(String path, ConventionRouter.PageMatch match, long revalidateSeconds) {
    }
}
