package dev.roots.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import dev.roots.Request;
import dev.roots.Response;
import dev.roots.Session;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HttpSupport {
    static final int MAX_REQUEST_BYTES = 1_048_576;

    private HttpSupport() {
    }

    static Request request(HttpExchange exchange, Map<String, String> parameters, Session session) throws IOException {
        var body = readBody(exchange);
        var contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        var form = contentType != null && contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")
                ? parameters(new String(body, StandardCharsets.UTF_8))
                : Map.<String, List<String>>of();
        return new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                parameters,
                parameters(exchange.getRequestURI().getRawQuery()),
                copyHeaders(exchange.getRequestHeaders()),
                form,
                body,
                session
        );
    }

    static Map<String, List<String>> parameters(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, List<String>>();
        for (var pair : encoded.split("&")) {
            var parts = pair.split("=", 2);
            var key = decode(parts[0]);
            var value = parts.length == 2 ? decode(parts[1]) : "";
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        var immutable = new LinkedHashMap<String, List<String>>();
        result.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return Map.copyOf(immutable);
    }

    static void send(HttpExchange exchange, Response response, boolean head) throws IOException {
        response.headers().forEach((name, values) -> exchange.getResponseHeaders().put(name, new ArrayList<>(values)));
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        var length = response.body().length;
        if (head || response.status() == 204 || response.status() == 304) {
            exchange.sendResponseHeaders(response.status(), -1);
        } else {
            exchange.sendResponseHeaders(response.status(), length);
            try (var output = exchange.getResponseBody()) {
                output.write(response.body());
            }
        }
        exchange.close();
    }

    static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        var result = new StringBuilder(value.length() + 16).append('"');
        for (var character : value.toCharArray()) {
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case '<' -> result.append("\\u003c");
                case '>' -> result.append("\\u003e");
                case '&' -> result.append("\\u0026");
                default -> {
                    if (character < 0x20) {
                        result.append("\\u%04x".formatted((int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        var declaredLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declaredLength != null && Long.parseLong(declaredLength) > MAX_REQUEST_BYTES) {
            throw new RequestTooLargeException();
        }
        try (var input = exchange.getRequestBody()) {
            var body = input.readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                throw new RequestTooLargeException();
            }
            return body;
        }
    }

    private static Map<String, List<String>> copyHeaders(Headers headers) {
        var copy = new LinkedHashMap<String, List<String>>();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Map.copyOf(copy);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    static final class RequestTooLargeException extends IOException {
    }
}
