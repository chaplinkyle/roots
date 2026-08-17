package dev.roots.internal;

import dev.roots.ApiRoute;
import dev.roots.Layout;
import dev.roots.Page;
import dev.roots.RootsConfig;
import dev.roots.annotation.Authorize;
import dev.roots.annotation.Prerender;
import dev.roots.annotation.Route;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ConventionRouter {
    private static final Comparator<RoutedClass<?>> SPECIFICITY = Comparator
            .<RoutedClass<?>>comparingInt(route -> route.pattern().staticSegments()).reversed()
            .thenComparingInt(route -> route.pattern().dynamicSegments())
            .thenComparing(Comparator.comparingInt((RoutedClass<?> route) -> route.pattern().segmentCount()).reversed());

    private final List<PageRoute> pages;
    private final List<ApiRouteDefinition> apiRoutes;
    private final PageRoute notFound;
    private final PageRoute errorPage;

    private ConventionRouter(
            List<PageRoute> pages,
            List<ApiRouteDefinition> apiRoutes,
            PageRoute notFound,
            PageRoute errorPage
    ) {
        this.pages = pages;
        this.apiRoutes = apiRoutes;
        this.notFound = notFound;
        this.errorPage = errorPage;
    }

    static ConventionRouter discover(RootsConfig config) {
        var manifest = RouteManifest.load(config);
        if (manifest.isPresent()) {
            return fromManifest(config, manifest.get());
        }
        return scan(config);
    }

    private static ConventionRouter fromManifest(RootsConfig config, RouteManifest.Routes manifest) {
        var pages = new ArrayList<PageRoute>();
        for (var entry : manifest.pages()) {
            var policies = new LinkedHashSet<String>();
            entry.layouts().forEach(layout -> addPolicies(config, layout, policies));
            addPolicies(config, entry.type(), policies);
            pages.add(new PageRoute(
                    RoutePattern.fromTemplate(entry.path()),
                    entry.type(),
                    entry.layouts(),
                    List.copyOf(policies)
            ));
        }
        pages.sort(SPECIFICITY);
        assertUnique(pages, "page");

        var apiRoutes = new ArrayList<ApiRouteDefinition>();
        for (var entry : manifest.apiRoutes()) {
            var policies = new LinkedHashSet<String>();
            addPolicies(config, entry.type(), policies);
            apiRoutes.add(new ApiRouteDefinition(
                    RoutePattern.fromTemplate(entry.path()),
                    entry.type(),
                    List.copyOf(policies)
            ));
        }
        apiRoutes.sort(SPECIFICITY);
        assertUnique(apiRoutes, "API route");
        var notFound = manifest.notFound().map(entry -> {
            var policies = new LinkedHashSet<String>();
            entry.layouts().forEach(layout -> addPolicies(config, layout, policies));
            addPolicies(config, entry.type(), policies);
            return new PageRoute(
                    RoutePattern.fromTemplate("/"),
                    entry.type(),
                    entry.layouts(),
                    List.copyOf(policies)
            );
        }).orElse(null);
        var errorPage = manifest.errorPage().map(entry -> new PageRoute(
                RoutePattern.fromTemplate("/"),
                entry.type(),
                List.of(),
                List.of()
        )).orElse(null);
        return new ConventionRouter(List.copyOf(pages), List.copyOf(apiRoutes), notFound, errorPage);
    }

    private static ConventionRouter scan(RootsConfig config) {
        var loader = config.applicationClass().getClassLoader();
        var pageClasses = ClassPathScanner.classes(config.pagesPackage(), loader);
        var layouts = new LinkedHashMap<String, Class<? extends Layout>>();

        for (var candidate : pageClasses) {
            if (candidate.getSimpleName().equals("Layout") && concrete(candidate, Layout.class)) {
                layouts.put(candidate.getPackageName(), candidate.asSubclass(Layout.class));
            }
        }

        var pages = new ArrayList<PageRoute>();
        PageRoute notFound = null;
        PageRoute errorPage = null;
        for (var candidate : pageClasses) {
            if (candidate.getPackageName().equals(config.pagesPackage())
                    && candidate.getSimpleName().equals("ErrorPage")
                    && concrete(candidate, Page.class)) {
                if (errorPage != null) {
                    throw new IllegalStateException("Multiple ErrorPage classes under " + config.pagesPackage());
                }
                if (candidate.isAnnotationPresent(Route.class)
                        || candidate.isAnnotationPresent(Prerender.class)
                        || candidate.isAnnotationPresent(Authorize.class)) {
                    throw new IllegalStateException("The root ErrorPage convention cannot declare "
                            + "@Route, @Prerender, or @Authorize: " + candidate.getName());
                }
                errorPage = new PageRoute(
                        RoutePattern.fromTemplate("/"),
                        candidate.asSubclass(Page.class),
                        List.of(),
                        List.of()
                );
                continue;
            }
            if (candidate.getPackageName().equals(config.pagesPackage())
                    && candidate.getSimpleName().equals("NotFound")
                    && concrete(candidate, Page.class)) {
                if (notFound != null) {
                    throw new IllegalStateException("Multiple NotFound page classes under " + config.pagesPackage());
                }
                if (candidate.isAnnotationPresent(Route.class)) {
                    throw new IllegalStateException("The root NotFound page convention cannot declare @Route: "
                            + candidate.getName());
                }
                if (candidate.isAnnotationPresent(Prerender.class)) {
                    throw new IllegalStateException("The root NotFound page convention cannot declare @Prerender: "
                            + candidate.getName());
                }
                var routeLayouts = layoutChain(candidate.getPackageName(), config.pagesPackage(), layouts);
                var policies = new LinkedHashSet<String>();
                routeLayouts.forEach(layout -> addPolicies(config, layout, policies));
                addPolicies(config, candidate, policies);
                notFound = new PageRoute(
                        RoutePattern.fromTemplate("/"),
                        candidate.asSubclass(Page.class),
                        routeLayouts,
                        List.copyOf(policies)
                );
                continue;
            }
            if (!candidate.getSimpleName().equals("Page") || !concrete(candidate, Page.class)) {
                continue;
            }
            var annotation = candidate.getAnnotation(Route.class);
            var pattern = annotation == null
                    ? RoutePattern.fromPackage(candidate.getPackageName(), config.pagesPackage(), "")
                    : RoutePattern.fromTemplate(annotation.value());
            var routeLayouts = layoutChain(candidate.getPackageName(), config.pagesPackage(), layouts);
            var policies = new LinkedHashSet<String>();
            routeLayouts.forEach(layout -> addPolicies(config, layout, policies));
            addPolicies(config, candidate, policies);
            pages.add(new PageRoute(
                    pattern,
                    candidate.asSubclass(Page.class),
                    routeLayouts,
                    List.copyOf(policies)
            ));
        }
        pages.sort(SPECIFICITY);
        assertUnique(pages, "page");
        if (pages.isEmpty()) {
            throw new IllegalStateException("No Page classes found under " + config.pagesPackage());
        }

        var apiRoutes = new ArrayList<ApiRouteDefinition>();
        for (var candidate : ClassPathScanner.classes(config.apiPackage(), loader)) {
            if (!candidate.getSimpleName().equals("Route") || !concrete(candidate, ApiRoute.class)) {
                continue;
            }
            var annotation = candidate.getAnnotation(Route.class);
            var pattern = annotation == null
                    ? RoutePattern.fromPackage(candidate.getPackageName(), config.apiPackage(), "/api")
                    : RoutePattern.fromTemplate(annotation.value());
            var policies = new LinkedHashSet<String>();
            addPolicies(config, candidate, policies);
            apiRoutes.add(new ApiRouteDefinition(
                    pattern,
                    candidate.asSubclass(ApiRoute.class),
                    List.copyOf(policies)
            ));
        }
        apiRoutes.sort(SPECIFICITY);
        assertUnique(apiRoutes, "API route");

        return new ConventionRouter(List.copyOf(pages), List.copyOf(apiRoutes), notFound, errorPage);
    }

    Optional<PageMatch> page(String path) {
        for (var route : pages) {
            var parameters = route.pattern().match(path);
            if (parameters.isPresent()) {
                return Optional.of(new PageMatch(route, parameters.get()));
            }
        }
        return Optional.empty();
    }

    Optional<ApiMatch> api(String path) {
        for (var route : apiRoutes) {
            var parameters = route.pattern().match(path);
            if (parameters.isPresent()) {
                return Optional.of(new ApiMatch(route, parameters.get()));
            }
        }
        return Optional.empty();
    }

    Optional<PageMatch> notFound() {
        return Optional.ofNullable(notFound).map(route -> new PageMatch(route, Map.of()));
    }

    Optional<PageMatch> errorPage() {
        return Optional.ofNullable(errorPage).map(route -> new PageMatch(route, Map.of()));
    }

    List<String> routes() {
        var routes = new ArrayList<String>();
        pages.forEach(route -> routes.add("PAGE " + route.pattern().display()));
        if (notFound != null) {
            routes.add("PAGE * (not found)");
        }
        if (errorPage != null) {
            routes.add("PAGE ! (error)");
        }
        apiRoutes.forEach(route -> routes.add("API  " + route.pattern().display()));
        return List.copyOf(routes);
    }

    List<PageRoute> pageRoutes() {
        return pages;
    }

    private static boolean concrete(Class<?> candidate, Class<?> contract) {
        return contract.isAssignableFrom(candidate)
                && !candidate.isInterface()
                && !Modifier.isAbstract(candidate.getModifiers());
    }

    private static List<Class<? extends Layout>> layoutChain(
            String pagePackage,
            String basePackage,
            Map<String, Class<? extends Layout>> layouts
    ) {
        var result = new ArrayList<Class<? extends Layout>>();
        var current = basePackage;
        var root = layouts.get(current);
        if (root != null) {
            result.add(root);
        }
        if (!pagePackage.equals(basePackage)) {
            var relative = pagePackage.substring(basePackage.length() + 1);
            for (var segment : relative.split("\\.")) {
                current += "." + segment;
                var layout = layouts.get(current);
                if (layout != null) {
                    result.add(layout);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void assertUnique(List<? extends RoutedClass<?>> routes, String kind) {
        var seen = new LinkedHashMap<String, Class<?>>();
        for (var route : routes) {
            var previous = seen.putIfAbsent(route.pattern().shape(), route.type());
            if (previous != null) {
                throw new IllegalStateException("Duplicate " + kind + " " + route.pattern().display()
                        + ": " + previous.getName() + " and " + route.type().getName());
            }
        }
    }

    private static void addPolicies(RootsConfig config, Class<?> type, LinkedHashSet<String> policies) {
        var authorization = type.getAnnotation(Authorize.class);
        if (authorization == null) {
            return;
        }
        if (authorization.value().length == 0) {
            throw new IllegalStateException("@Authorize must name at least one policy: " + type.getName());
        }
        for (var policy : authorization.value()) {
            if (policy == null || !policy.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
                throw new IllegalStateException("Invalid authorization policy on " + type.getName() + ": " + policy);
            }
            if (!config.authorizationPolicies().containsKey(policy)) {
                throw new IllegalStateException("No authorization policy named '" + policy
                        + "' is registered for " + type.getName());
            }
            policies.add(policy);
        }
    }

    private interface RoutedClass<T> {
        RoutePattern pattern();
        Class<? extends T> type();
    }

    record PageRoute(
            RoutePattern pattern,
            Class<? extends Page> type,
            List<Class<? extends Layout>> layouts,
            List<String> authorizationPolicies
    ) implements RoutedClass<Page> {
    }

    record ApiRouteDefinition(
            RoutePattern pattern,
            Class<? extends ApiRoute> type,
            List<String> authorizationPolicies
    )
            implements RoutedClass<ApiRoute> {
    }

    record PageMatch(PageRoute route, Map<String, String> parameters) {
    }

    record ApiMatch(ApiRouteDefinition route, Map<String, String> parameters) {
    }
}
