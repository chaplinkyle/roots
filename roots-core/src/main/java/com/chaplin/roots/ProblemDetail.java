package com.chaplin.roots;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A bounded RFC 9457 API error with string-valued extensions. Only include public
 * diagnostics; exception messages, credentials, and stack traces are not sanitized.
 *
 * @param type absolute problem-type URI, normally an application documentation URL
 * @param status HTTP error status from 400 through 599
 * @param title short human-readable problem title
 * @param detail occurrence-specific explanation, or empty
 * @param instance URI reference identifying this occurrence, or {@code null}
 * @param extensions up to 16 application-specific string members
 */
public record ProblemDetail(URI type, int status, String title, String detail,
        URI instance, Map<String, String> extensions) {
    private static final Set<String> RESERVED = Set.of("type", "status", "title", "detail", "instance");

    /** Validates and defensively copies the error representation. */
    public ProblemDetail {
        Objects.requireNonNull(type, "type");
        if (!type.isAbsolute() || type.toString().length() > 2048) {
            throw new IllegalArgumentException("Problem type must be an absolute URI of at most 2048 characters");
        }
        if (status < 400 || status > 599) throw new IllegalArgumentException("Problem status must be 400 through 599");
        bounded(title, 256, "title");
        if (title.isBlank()) throw new IllegalArgumentException("Problem title must not be blank");
        bounded(detail, 4096, "detail");
        if (instance != null && instance.toString().length() > 2048) {
            throw new IllegalArgumentException("Problem instance is too long");
        }
        extensions = Map.copyOf(Objects.requireNonNull(extensions, "extensions"));
        if (extensions.size() > 16) throw new IllegalArgumentException("At most 16 problem extensions are supported");
        extensions.forEach((name, value) -> {
            if (!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || RESERVED.contains(name)) {
                throw new IllegalArgumentException("Invalid or reserved problem extension name");
            }
            bounded(value, 2048, "extension");
        });
    }

    /** Creates an application-defined problem without extensions or an occurrence URI.
     * @param type absolute problem-type URI
     * @param status HTTP error status
     * @param title short title
     * @param detail public explanation
     * @return problem detail */
    public static ProblemDetail of(URI type, int status, String title, String detail) {
        return new ProblemDetail(type, status, title, detail, null, Map.of());
    }

    /** Returns a copy with an occurrence URI.
     * @param occurrence occurrence URI reference
     * @return updated problem */
    public ProblemDetail withInstance(URI occurrence) {
        return new ProblemDetail(type, status, title, detail, Objects.requireNonNull(occurrence), extensions);
    }

    /** Returns a copy with a string extension, such as a trace identifier.
     * @param name non-reserved extension member
     * @param value extension text
     * @return updated problem */
    public ProblemDetail withExtension(String name, String value) {
        var updated = new LinkedHashMap<>(extensions);
        updated.put(name, value);
        return new ProblemDetail(type, status, title, detail, instance, updated);
    }

    /** Returns a JSON error whose HTTP status matches its body, with caching disabled.
     * @return {@code application/problem+json} response */
    public Response response() {
        var json = new StringBuilder("{\"type\":").append(quote(type.toString()))
                .append(",\"status\":").append(status).append(",\"title\":").append(quote(title))
                .append(",\"detail\":").append(quote(detail));
        if (instance != null) json.append(",\"instance\":").append(quote(instance.toString()));
        extensions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                json.append(',').append(quote(entry.getKey())).append(':').append(quote(entry.getValue())));
        return Response.of(status, "application/problem+json", json.append('}').toString())
                .withHeader("Cache-Control", "no-store");
    }

    private static void bounded(String value, int maximum, String name) {
        if (Objects.requireNonNull(value, name).length() > maximum) {
            throw new IllegalArgumentException("Problem " + name + " is too long");
        }
    }

    private static String quote(String value) {
        var result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                default -> {
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        result.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            result.append(Character.forDigit((character >> shift) & 15, 16));
                        }
                    } else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }
}
