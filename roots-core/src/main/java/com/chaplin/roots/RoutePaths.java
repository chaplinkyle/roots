package com.chaplin.roots;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Shared route-path normalization used by the runtime and build-time tooling. */
public final class RoutePaths {
    private RoutePaths() {
    }

    /**
     * Derives a canonical route from a convention package.
     *
     * <p>Segments beginning with {@code group_} do not affect the URL,
     * underscores become hyphens, {@code $name} becomes {@code {name}}, and
     * {@code $$name} becomes a terminal {@code {*name}} catch-all.</p>
     *
     * @param packageName concrete page or API-route package
     * @param basePackage configured convention base package
     * @param prefix route prefix such as {@code /api}, or an empty string
     * @return canonical absolute route
     */
    public static String fromPackage(String packageName, String basePackage, String prefix) {
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(basePackage, "basePackage");
        Objects.requireNonNull(prefix, "prefix");
        if (!packageName.equals(basePackage) && !packageName.startsWith(basePackage + ".")) {
            throw new IllegalArgumentException(packageName + " is not beneath " + basePackage);
        }
        var segments = new ArrayList<String>();
        if (!prefix.isBlank()) {
            segments.addAll(segments(fromTemplate(prefix)));
        }
        var relative = packageName.equals(basePackage)
                ? ""
                : packageName.substring(basePackage.length() + 1);
        if (!relative.isBlank()) {
            for (var value : relative.split("\\.")) {
                if (value.startsWith("group_")) {
                    continue;
                }
                if (value.startsWith("$$")) {
                    if (value.length() == 2) {
                        throw new IllegalArgumentException("Catch-all route packages need a name: " + packageName);
                    }
                    segments.add("{*" + value.substring(2) + "}");
                } else if (value.startsWith("$")) {
                    if (value.length() == 1) {
                        throw new IllegalArgumentException("Dynamic route packages need a name: " + packageName);
                    }
                    segments.add("{" + value.substring(1) + "}");
                } else {
                    segments.add(value.replace('_', '-'));
                }
            }
        }
        validateCatchAll(segments, packageName);
        return fromTemplate(path(segments));
    }

    /**
     * Validates and canonicalizes an annotation-provided route template.
     *
     * @param template absolute route template
     * @return canonical absolute route without a trailing slash
     */
    public static String fromTemplate(String template) {
        if (template == null || !template.startsWith("/") || template.startsWith("//")) {
            throw new IllegalArgumentException("Routes must start with one '/': " + template);
        }
        var normalized = template.length() > 1 && template.endsWith("/")
                ? template.substring(0, template.length() - 1)
                : template;
        if (normalized.equals("/")) {
            return normalized;
        }
        var segments = new ArrayList<String>();
        var parameterNames = new HashSet<String>();
        for (var value : normalized.substring(1).split("/", -1)) {
            if (value.matches("\\{\\*[A-Za-z][A-Za-z0-9_]*}")) {
                validateUniqueParameter(value.substring(2, value.length() - 1), parameterNames, template);
                segments.add(value);
            } else if (value.matches("\\{[A-Za-z][A-Za-z0-9_]*}")) {
                validateUniqueParameter(value.substring(1, value.length() - 1), parameterNames, template);
                segments.add(value);
            } else if (value.matches("[A-Za-z0-9][A-Za-z0-9._~-]*")) {
                segments.add(value);
            } else {
                throw new IllegalArgumentException("Invalid route segment '" + value + "' in " + template);
            }
        }
        validateCatchAll(segments, template);
        return path(segments);
    }

    /**
     * Returns a canonical match shape that ignores parameter names.
     *
     * <p>For example, {@code /users/{id}} and {@code /users/{name}} both have
     * the shape {@code /users/{}} and therefore conflict.</p>
     *
     * @param template route template
     * @return canonical ambiguity-detection shape
     */
    public static String shape(String template) {
        var canonical = fromTemplate(template);
        if (canonical.equals("/")) {
            return canonical;
        }
        var shaped = new ArrayList<String>();
        for (var segment : segments(canonical)) {
            if (segment.startsWith("{*")) {
                shaped.add("{*}");
            } else if (segment.startsWith("{")) {
                shaped.add("{}");
            } else {
                shaped.add(segment);
            }
        }
        return path(shaped);
    }

    private static List<String> segments(String path) {
        return path.equals("/") ? List.of() : List.of(path.substring(1).split("/"));
    }

    private static void validateCatchAll(List<String> segments, String source) {
        for (var index = 0; index < segments.size() - 1; index++) {
            if (segments.get(index).startsWith("{*")) {
                throw new IllegalArgumentException("A catch-all segment must be last: " + source);
            }
        }
    }

    private static void validateUniqueParameter(String name, HashSet<String> names, String source) {
        if (!names.add(name)) {
            throw new IllegalArgumentException("Duplicate route parameter '" + name + "' in " + source);
        }
    }

    private static String path(List<String> segments) {
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }
}
