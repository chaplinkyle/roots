package dev.roots.internal;

import dev.roots.ApiRoute;
import dev.roots.Layout;
import dev.roots.Page;
import dev.roots.RootsConfig;
import dev.roots.annotation.Route;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

    private ConventionRouter(List<PageRoute> pages, List<ApiRouteDefinition> apiRoutes) {
        this.pages = pages;
        this.apiRoutes = apiRoutes;
    }

    static ConventionRouter discover(RootsConfig config) {
        var loader = config.applicationClass().getClassLoader();
        var pageClasses = ClassPathScanner.classes(config.pagesPackage(), loader);
        var layouts = new LinkedHashMap<String, Class<? extends Layout>>();

        for (var candidate : pageClasses) {
            if (candidate.getSimpleName().equals("Layout") && concrete(candidate, Layout.class)) {
                layouts.put(candidate.getPackageName(), candidate.asSubclass(Layout.class));
            }
        }

        var pages = new ArrayList<PageRoute>();
        for (var candidate : pageClasses) {
            if (!candidate.getSimpleName().equals("Page") || !concrete(candidate, Page.class)) {
                continue;
            }
            var annotation = candidate.getAnnotation(Route.class);
            var pattern = annotation == null
                    ? RoutePattern.fromPackage(candidate.getPackageName(), config.pagesPackage(), "")
                    : RoutePattern.fromTemplate(annotation.value());
            var routeLayouts = layoutChain(candidate.getPackageName(), config.pagesPackage(), layouts);
            pages.add(new PageRoute(pattern, candidate.asSubclass(Page.class), routeLayouts));
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
            apiRoutes.add(new ApiRouteDefinition(pattern, candidate.asSubclass(ApiRoute.class)));
        }
        apiRoutes.sort(SPECIFICITY);
        assertUnique(apiRoutes, "API route");

        return new ConventionRouter(List.copyOf(pages), List.copyOf(apiRoutes));
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

    List<String> routes() {
        var routes = new ArrayList<String>();
        pages.forEach(route -> routes.add("PAGE " + route.pattern().display()));
        apiRoutes.forEach(route -> routes.add("API  " + route.pattern().display()));
        return List.copyOf(routes);
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
            var previous = seen.putIfAbsent(route.pattern().display(), route.type());
            if (previous != null) {
                throw new IllegalStateException("Duplicate " + kind + " " + route.pattern().display()
                        + ": " + previous.getName() + " and " + route.type().getName());
            }
        }
    }

    private interface RoutedClass<T> {
        RoutePattern pattern();
        Class<? extends T> type();
    }

    record PageRoute(
            RoutePattern pattern,
            Class<? extends Page> type,
            List<Class<? extends Layout>> layouts
    ) implements RoutedClass<Page> {
    }

    record ApiRouteDefinition(RoutePattern pattern, Class<? extends ApiRoute> type)
            implements RoutedClass<ApiRoute> {
    }

    record PageMatch(PageRoute route, Map<String, String> parameters) {
    }

    record ApiMatch(ApiRouteDefinition route, Map<String, String> parameters) {
    }
}
