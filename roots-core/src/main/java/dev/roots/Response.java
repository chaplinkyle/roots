package dev.roots;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Response(int status, Map<String, List<String>> headers, byte[] body) {
    public Response {
        headers = Map.copyOf(headers);
        body = body.clone();
    }

    public static Response text(int status, String body) {
        return of(status, "text/plain; charset=utf-8", body);
    }

    public static Response html(int status, String body) {
        return of(status, "text/html; charset=utf-8", body);
    }

    public static Response json(int status, String body) {
        return of(status, "application/json; charset=utf-8", body);
    }

    public static Response noContent() {
        return new Response(204, Map.of(), new byte[0]);
    }

    public static Response redirect(String location) {
        return new Response(303, Map.of("Location", List.of(location)), new byte[0]);
    }

    public static Response methodNotAllowed(String method) {
        return text(405, "Method not allowed: " + method);
    }

    public static Response of(int status, String contentType, String body) {
        return new Response(
                status,
                Map.of("Content-Type", List.of(contentType)),
                body.getBytes(StandardCharsets.UTF_8)
        );
    }

    public Response withHeader(String name, String value) {
        var updated = new LinkedHashMap<>(headers);
        updated.put(name, List.of(value));
        return new Response(status, updated, body);
    }
}
