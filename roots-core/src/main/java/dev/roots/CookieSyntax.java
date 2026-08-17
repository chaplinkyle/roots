package dev.roots;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared strict cookie syntax and tolerant request parsing. */
final class CookieSyntax {
    private static final int MAX_NAME_LENGTH = 256;
    private static final int MAX_VALUE_LENGTH = 4_096;
    private static final int MAX_COOKIES = 128;
    private static final int MAX_COOKIE_HEADER_LENGTH = 16_384;

    private CookieSyntax() {
    }

    static String requireName(String name) {
        Objects.requireNonNull(name, "cookie name");
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH
                || name.chars().anyMatch(character -> !httpToken(character))) {
            throw new IllegalArgumentException("Cookie names must be nonempty ASCII HTTP tokens of at most 256 characters");
        }
        return name;
    }

    static String requireValue(String value) {
        Objects.requireNonNull(value, "cookie value");
        if (value.length() > MAX_VALUE_LENGTH || value.chars().anyMatch(character -> !cookieOctet(character))) {
            throw new IllegalArgumentException(
                    "Cookie values must contain at most 4096 unquoted RFC 6265 cookie-octet characters"
            );
        }
        return value;
    }

    static Map<String, String> parse(Map<String, List<String>> headers) {
        var cookies = new LinkedHashMap<String, String>();
        var remaining = MAX_COOKIE_HEADER_LENGTH;
        outer:
        for (var entry : Objects.requireNonNull(headers, "headers").entrySet()) {
            var headerName = entry.getKey();
            if (!headerName.equalsIgnoreCase("Cookie")) {
                continue;
            }
            for (var header : entry.getValue()) {
                if (header.length() > remaining) break outer;
                remaining -= header.length();
                for (var fragment : header.split(";", -1)) {
                    var separator = fragment.indexOf('=');
                    if (separator <= 0) {
                        continue;
                    }
                    var name = fragment.substring(0, separator).strip();
                    var value = fragment.substring(separator + 1).strip();
                    if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                        value = value.substring(1, value.length() - 1);
                    }
                    try {
                        requireName(name);
                        requireValue(value);
                        if (!cookies.containsKey(name) && cookies.size() >= MAX_COOKIES) break outer;
                        cookies.putIfAbsent(name, value);
                    } catch (IllegalArgumentException | NullPointerException ignored) {
                        // A malformed application cookie must not make the entire request unusable.
                    }
                }
            }
        }
        return Map.copyOf(cookies);
    }

    static Map<String, String> copy(Map<String, String> cookies) {
        var copied = new LinkedHashMap<String, String>();
        Objects.requireNonNull(cookies, "cookies");
        if (cookies.size() > MAX_COOKIES) {
            throw new IllegalArgumentException("Cookie snapshots may contain at most 128 entries");
        }
        cookies.forEach((name, value) ->
                copied.put(requireName(name), requireValue(value))
        );
        return Map.copyOf(copied);
    }

    private static boolean cookieOctet(int character) {
        return character == 0x21
                || character >= 0x23 && character <= 0x2b
                || character >= 0x2d && character <= 0x3a
                || character >= 0x3c && character <= 0x5b
                || character >= 0x5d && character <= 0x7e;
    }

    private static boolean httpToken(int character) {
        if (character <= 0x20 || character >= 0x7f) return false;
        return switch (character) {
            case '(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=', '{', '}' -> false;
            default -> true;
        };
    }
}
