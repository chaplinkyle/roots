package dev.roots.internal;

import dev.roots.ApiRoute;
import dev.roots.Layout;
import dev.roots.Page;
import dev.roots.RootsConfig;
import dev.roots.RoutePaths;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

final class RouteManifest {
    private static final String HEADER = "ROOTS_ROUTE_MANIFEST";
    private static final String VERSION = "3";
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_ROUTES = 10_000;

    private RouteManifest() {
    }

    static Optional<Routes> load(RootsConfig config) {
        return load(config, config.applicationClass().getClassLoader());
    }

    static Optional<Routes> load(RootsConfig config, ClassLoader loader) {
        var resourceName = "META-INF/roots/routes/" + config.applicationClass().getName() + ".routes";
        try {
            var resources = Collections.list(loader.getResources(resourceName));
            if (resources.isEmpty()) {
                return Optional.empty();
            }
            if (resources.size() > 1) {
                throw new IllegalStateException("Multiple Roots route manifests found for "
                        + config.applicationClass().getName() + ": " + resources);
            }
            var resource = resources.getFirst();
            final byte[] bytes;
            try (var input = resource.openStream()) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) {
                throw invalid(resourceName, "manifest exceeds " + MAX_BYTES + " bytes");
            }
            return parse(config, loader, resourceName, new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read Roots route manifest " + resourceName, exception);
        }
    }

    static Optional<Routes> parse(
            RootsConfig config,
            ClassLoader loader,
            String resourceName,
            String content
    ) {
        var lines = content.replace("\r\n", "\n").split("\n", -1);
        if (lines.length == 0 || lines[0].isBlank()) {
            throw invalid(resourceName, "missing header");
        }
        var header = lines[0].split("\t", -1);
        if (header.length != 5 || !header[0].equals(HEADER)
                || !header[1].equals("1") && !header[1].equals("2") && !header[1].equals(VERSION)) {
            throw invalid(resourceName, "unsupported or malformed header");
        }
        var version = Integer.parseInt(header[1]);
        if (!header[2].equals(config.applicationClass().getName())) {
            throw invalid(resourceName, "application anchor is " + header[2]);
        }
        if (!header[3].equals(config.pagesPackage()) || !header[4].equals(config.apiPackage())) {
            return Optional.empty();
        }

        var pages = new ArrayList<PageEntry>();
        var apiRoutes = new ArrayList<ApiEntry>();
        PageEntry notFound = null;
        PageEntry errorPage = null;
        var routeCount = 0;
        for (var index = 1; index < lines.length; index++) {
            var line = lines[index];
            if (line.isEmpty() && index == lines.length - 1) {
                continue;
            }
            if (line.isBlank() || ++routeCount > MAX_ROUTES) {
                throw invalid(resourceName, line.isBlank()
                        ? "blank route row at line " + (index + 1)
                        : "manifest exceeds " + MAX_ROUTES + " routes");
            }
            var fields = line.split("\t", -1);
            if (fields.length != 4) {
                throw invalid(resourceName, "route row " + (index + 1) + " must have four fields");
            }
            final String path;
            try {
                path = RoutePaths.fromTemplate(fields[1]);
            } catch (IllegalArgumentException exception) {
                throw invalid(resourceName, "invalid path at line " + (index + 1) + ": " + exception.getMessage());
            }
            if (!path.equals(fields[1])) {
                throw invalid(resourceName, "non-canonical path at line " + (index + 1) + ": " + fields[1]);
            }
            switch (fields[0]) {
                case "PAGE" -> pages.add(new PageEntry(
                        path,
                        loadConcrete(loader, fields[2], Page.class, resourceName),
                        loadLayouts(loader, fields[3], resourceName)
                ));
                case "API" -> {
                    if (!fields[3].isEmpty()) {
                        throw invalid(resourceName, "API route has layouts at line " + (index + 1));
                    }
                    apiRoutes.add(new ApiEntry(
                            path,
                            loadConcrete(loader, fields[2], ApiRoute.class, resourceName)
                    ));
                }
                case "NOT_FOUND" -> {
                    if (version < 2) {
                        throw invalid(resourceName, "NOT_FOUND requires manifest version 2 or newer");
                    }
                    if (!path.equals("/")) {
                        throw invalid(resourceName, "NOT_FOUND path must be /");
                    }
                    if (notFound != null) {
                        throw invalid(resourceName, "manifest contains multiple NOT_FOUND pages");
                    }
                    notFound = new PageEntry(
                            path,
                            loadConcrete(loader, fields[2], Page.class, resourceName),
                            loadLayouts(loader, fields[3], resourceName)
                    );
                }
                case "ERROR_PAGE" -> {
                    if (version < 3) {
                        throw invalid(resourceName, "ERROR_PAGE requires manifest version 3 or newer");
                    }
                    if (!path.equals("/")) {
                        throw invalid(resourceName, "ERROR_PAGE path must be /");
                    }
                    if (!fields[3].isEmpty()) {
                        throw invalid(resourceName, "ERROR_PAGE cannot declare layouts");
                    }
                    if (errorPage != null) {
                        throw invalid(resourceName, "manifest contains multiple ERROR_PAGE pages");
                    }
                    errorPage = new PageEntry(
                            path,
                            loadConcrete(loader, fields[2], Page.class, resourceName),
                            List.of()
                    );
                }
                default -> throw invalid(resourceName, "unknown route kind at line " + (index + 1));
            }
        }
        if (pages.isEmpty()) {
            throw invalid(resourceName, "manifest contains no pages");
        }
        return Optional.of(new Routes(
                List.copyOf(pages),
                List.copyOf(apiRoutes),
                Optional.ofNullable(notFound),
                Optional.ofNullable(errorPage)
        ));
    }

    private static List<Class<? extends Layout>> loadLayouts(
            ClassLoader loader,
            String value,
            String resourceName
    ) {
        if (value.isEmpty()) {
            return List.of();
        }
        var layouts = new ArrayList<Class<? extends Layout>>();
        for (var name : value.split(",", -1)) {
            if (name.isBlank()) {
                throw invalid(resourceName, "blank layout class name");
            }
            layouts.add(loadConcrete(loader, name, Layout.class, resourceName));
        }
        return List.copyOf(layouts);
    }

    private static <T> Class<? extends T> loadConcrete(
            ClassLoader loader,
            String name,
            Class<T> contract,
            String resourceName
    ) {
        if (name.isBlank() || name.chars().anyMatch(Character::isISOControl)) {
            throw invalid(resourceName, "invalid class name");
        }
        try {
            var candidate = Class.forName(name, false, loader);
            if (!contract.isAssignableFrom(candidate)
                    || candidate.isInterface()
                    || Modifier.isAbstract(candidate.getModifiers())) {
                throw invalid(resourceName, name + " is not a concrete " + contract.getSimpleName());
            }
            return candidate.asSubclass(contract);
        } catch (ClassNotFoundException | LinkageError exception) {
            throw new IllegalStateException("Could not load route-manifest class " + name
                    + " from " + resourceName, exception);
        }
    }

    private static IllegalStateException invalid(String resourceName, String reason) {
        return new IllegalStateException("Invalid Roots route manifest " + resourceName + ": " + reason);
    }

    record Routes(
            List<PageEntry> pages,
            List<ApiEntry> apiRoutes,
            Optional<PageEntry> notFound,
            Optional<PageEntry> errorPage
    ) {
    }

    record PageEntry(
            String path,
            Class<? extends Page> type,
            List<Class<? extends Layout>> layouts
    ) {
    }

    record ApiEntry(String path, Class<? extends ApiRoute> type) {
    }
}
