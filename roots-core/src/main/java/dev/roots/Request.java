package dev.roots;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public record Request(
        String method,
        String path,
        Map<String, String> parameters,
        Map<String, List<String>> query,
        Map<String, List<String>> headers,
        Map<String, List<String>> form,
        byte[] body,
        Session session
) {
    public Request {
        method = method.toUpperCase(Locale.ROOT);
        parameters = Map.copyOf(parameters);
        query = Map.copyOf(query);
        headers = Map.copyOf(headers);
        form = Map.copyOf(form);
        body = body.clone();
    }

    public Optional<String> header(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
    }

    public Optional<String> queryValue(String name) {
        return query.getOrDefault(name, List.of()).stream().findFirst();
    }

    public Optional<String> formValue(String name) {
        return form.getOrDefault(name, List.of()).stream().findFirst();
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
