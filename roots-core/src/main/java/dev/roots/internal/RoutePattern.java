package dev.roots.internal;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class RoutePattern {
    private final String display;
    private final List<Segment> segments;
    private final int staticSegments;

    private RoutePattern(List<Segment> segments) {
        this.segments = List.copyOf(segments);
        this.staticSegments = (int) segments.stream().filter(StaticSegment.class::isInstance).count();
        var path = new StringBuilder();
        for (var segment : segments) {
            path.append('/').append(segment.display());
        }
        this.display = path.isEmpty() ? "/" : path.toString();
    }

    static RoutePattern fromPackage(String packageName, String basePackage, String prefix) {
        if (!packageName.equals(basePackage) && !packageName.startsWith(basePackage + ".")) {
            throw new IllegalArgumentException(packageName + " is not beneath " + basePackage);
        }
        var relative = packageName.equals(basePackage)
                ? ""
                : packageName.substring(basePackage.length() + 1);
        var segments = new ArrayList<Segment>();
        addTemplateSegments(prefix, segments);
        if (!relative.isBlank()) {
            for (var value : relative.split("\\.")) {
                if (value.startsWith("group_")) {
                    continue;
                }
                if (value.startsWith("$$")) {
                    if (value.length() == 2) {
                        throw new IllegalArgumentException("Catch-all route packages need a name: " + packageName);
                    }
                    segments.add(new CatchAllSegment(value.substring(2)));
                } else if (value.startsWith("$")) {
                    if (value.length() == 1) {
                        throw new IllegalArgumentException("Dynamic route packages need a name: " + packageName);
                    }
                    segments.add(new ParameterSegment(value.substring(1)));
                } else {
                    segments.add(new StaticSegment(value.replace('_', '-')));
                }
            }
        }
        validateCatchAll(segments, packageName);
        return new RoutePattern(segments);
    }

    static RoutePattern fromTemplate(String template) {
        if (template == null || !template.startsWith("/") || template.startsWith("//")) {
            throw new IllegalArgumentException("Routes must start with one '/': " + template);
        }
        var segments = new ArrayList<Segment>();
        addTemplateSegments(template, segments);
        validateCatchAll(segments, template);
        return new RoutePattern(segments);
    }

    private static void addTemplateSegments(String template, List<Segment> segments) {
        if (template == null || template.isBlank() || template.equals("/")) {
            return;
        }
        var normalized = template.startsWith("/") ? template.substring(1) : template;
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        for (var value : normalized.split("/")) {
            if (value.matches("\\{\\*[A-Za-z][A-Za-z0-9_]*}")) {
                segments.add(new CatchAllSegment(value.substring(2, value.length() - 1)));
            } else if (value.matches("\\{[A-Za-z][A-Za-z0-9_]*}")) {
                segments.add(new ParameterSegment(value.substring(1, value.length() - 1)));
            } else if (value.matches("[A-Za-z0-9][A-Za-z0-9._~-]*")) {
                segments.add(new StaticSegment(value));
            } else {
                throw new IllegalArgumentException("Invalid route segment '" + value + "' in " + template);
            }
        }
    }

    private static void validateCatchAll(List<Segment> segments, String source) {
        for (var index = 0; index < segments.size() - 1; index++) {
            if (segments.get(index) instanceof CatchAllSegment) {
                throw new IllegalArgumentException("A catch-all segment must be last: " + source);
            }
        }
    }

    Optional<Map<String, String>> match(String rawPath) {
        var normalized = rawPath == null || rawPath.isBlank() ? "/" : rawPath;
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        var values = normalized.equals("/")
                ? List.<String>of()
                : List.of(normalized.substring(1).split("/", -1));

        var parameters = new LinkedHashMap<String, String>();
        var valueIndex = 0;
        for (var segment : segments) {
            if (segment instanceof CatchAllSegment catchAll) {
                if (valueIndex >= values.size()) {
                    return Optional.empty();
                }
                var remaining = values.subList(valueIndex, values.size()).stream()
                        .map(RoutePattern::decodePathSegment)
                        .toList();
                parameters.put(catchAll.name(), String.join("/", remaining));
                valueIndex = values.size();
                break;
            }
            if (valueIndex >= values.size()) {
                return Optional.empty();
            }
            var value = decodePathSegment(values.get(valueIndex++));
            if (segment instanceof StaticSegment fixed && !fixed.value().equals(value)) {
                return Optional.empty();
            }
            if (segment instanceof ParameterSegment parameter) {
                parameters.put(parameter.name(), value);
            }
        }
        return valueIndex == values.size() ? Optional.of(Map.copyOf(parameters)) : Optional.empty();
    }

    int staticSegments() {
        return staticSegments;
    }

    int segmentCount() {
        return segments.size();
    }

    int dynamicSegments() {
        return segments.size() - staticSegments;
    }

    String display() {
        return display;
    }

    private static String decodePathSegment(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private sealed interface Segment permits StaticSegment, ParameterSegment, CatchAllSegment {
        String display();
    }

    private record StaticSegment(String value) implements Segment {
        @Override
        public String display() { return value; }
    }

    private record ParameterSegment(String name) implements Segment {
        @Override
        public String display() { return "{" + name + "}"; }
    }

    private record CatchAllSegment(String name) implements Segment {
        @Override
        public String display() { return "{*" + name + "}"; }
    }
}
