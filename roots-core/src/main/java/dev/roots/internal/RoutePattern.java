package dev.roots.internal;

import dev.roots.RoutePaths;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class RoutePattern {
    private final String display;
    private final String shape;
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
        this.shape = RoutePaths.shape(display);
    }

    static RoutePattern fromPackage(String packageName, String basePackage, String prefix) {
        return fromCanonical(RoutePaths.fromPackage(packageName, basePackage, prefix));
    }

    static RoutePattern fromTemplate(String template) {
        return fromCanonical(RoutePaths.fromTemplate(template));
    }

    private static RoutePattern fromCanonical(String template) {
        var segments = new ArrayList<Segment>();
        addTemplateSegments(template, segments);
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

    String shape() {
        return shape;
    }

    List<String> parameterNames() {
        return segments.stream()
                .filter(segment -> !(segment instanceof StaticSegment))
                .map(segment -> switch (segment) {
                    case ParameterSegment parameter -> parameter.name();
                    case CatchAllSegment catchAll -> catchAll.name();
                    default -> throw new IllegalStateException("Unexpected static route segment");
                })
                .toList();
    }

    String expand(Map<String, String> parameters) {
        if (!parameters.keySet().equals(java.util.Set.copyOf(parameterNames()))) {
            throw new IllegalArgumentException("Static path parameters for " + display
                    + " must be exactly " + parameterNames() + " but were " + parameters.keySet());
        }
        var path = new StringBuilder();
        for (var segment : segments) {
            path.append('/');
            switch (segment) {
                case StaticSegment fixed -> path.append(fixed.value());
                case ParameterSegment parameter -> path.append(encodePathSegment(required(parameters, parameter.name())));
                case CatchAllSegment catchAll -> {
                    var value = required(parameters, catchAll.name());
                    var parts = value.split("/", -1);
                    if (parts.length == 0 || java.util.Arrays.stream(parts).anyMatch(String::isBlank)) {
                        throw new IllegalArgumentException("Catch-all parameter " + catchAll.name()
                                + " for " + display + " must contain nonblank path segments");
                    }
                    path.append(java.util.Arrays.stream(parts)
                            .map(RoutePattern::encodePathSegment)
                            .collect(java.util.stream.Collectors.joining("/")));
                }
            }
        }
        return path.isEmpty() ? "/" : path.toString();
    }

    private static String required(Map<String, String> parameters, String name) {
        var value = parameters.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Static path parameter " + name + " must not be blank");
        }
        return value;
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
