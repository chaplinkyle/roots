package com.chaplin.roots.internal;

import com.chaplin.roots.ClientConnection;
import com.chaplin.roots.ProxyPolicy;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Internal strict forwarded-header resolver exposed through {@link ProxyPolicy}. */
public final class TrustedProxyPolicy implements ProxyPolicy {
    private static final int MAX_HEADER_LENGTH = 8_192;
    private static final int MAX_HOPS = 32;
    private final List<Cidr> trusted;

    private TrustedProxyPolicy(List<Cidr> trusted) {
        this.trusted = List.copyOf(trusted);
    }

    /** Creates a validated policy for the public factory.
     * @param trustedCidrs trusted IPv4 or IPv6 ranges
     * @return proxy policy
     */
    public static ProxyPolicy create(List<String> trustedCidrs) {
        Objects.requireNonNull(trustedCidrs, "trustedCidrs");
        if (trustedCidrs.isEmpty()) {
            return ProxyPolicy.directOnly();
        }
        return new TrustedProxyPolicy(trustedCidrs.stream().map(Cidr::parse).toList());
    }

    @Override
    public ClientConnection resolve(ClientConnection direct, Map<String, List<String>> headers) {
        Objects.requireNonNull(direct, "direct");
        Objects.requireNonNull(headers, "headers");
        var peer = direct.clientAddress();
        if (peer.isEmpty() || !isTrusted(peer.orElseThrow())) {
            return direct;
        }
        var forwarded = values(headers, "Forwarded");
        if (!forwarded.isEmpty()) {
            return standard(direct, forwarded).orElse(direct);
        }
        return legacy(direct, headers).orElse(direct);
    }

    private Optional<ClientConnection> standard(ClientConnection direct, List<String> values) {
        var joined = boundedJoin(values);
        if (joined == null) {
            return Optional.empty();
        }
        var elements = split(joined, ',');
        if (elements.isEmpty() || elements.size() > MAX_HOPS) {
            return Optional.empty();
        }
        var addresses = new ArrayList<InetAddress>();
        Map<String, String> nearest = null;
        for (var element : elements) {
            var parameters = parameters(element);
            if (parameters == null || !parameters.containsKey("for")) {
                return Optional.empty();
            }
            var parsed = address(parameters.get("for"));
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            addresses.add(parsed.orElseThrow());
            nearest = parameters;
        }
        var client = client(addresses);
        var scheme = validatedScheme(nearest.get("proto")).orElse(direct.scheme());
        var authority = validatedAuthority(nearest.get("host"), scheme).orElse(direct.authority());
        return Optional.of(direct.forwarded(client, scheme, authority));
    }

    private Optional<ClientConnection> legacy(ClientConnection direct, Map<String, List<String>> headers) {
        var forwardedFor = values(headers, "X-Forwarded-For");
        if (forwardedFor.isEmpty()) {
            return Optional.empty();
        }
        var joined = boundedJoin(forwardedFor);
        if (joined == null) {
            return Optional.empty();
        }
        var tokens = split(joined, ',');
        if (tokens.isEmpty() || tokens.size() > MAX_HOPS) {
            return Optional.empty();
        }
        var addresses = new ArrayList<InetAddress>();
        for (var token : tokens) {
            var parsed = address(token);
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            addresses.add(parsed.orElseThrow());
        }
        var scheme = lastToken(headers, "X-Forwarded-Proto")
                .flatMap(TrustedProxyPolicy::validatedScheme)
                .orElse(direct.scheme());
        var authority = lastToken(headers, "X-Forwarded-Host")
                .flatMap(value -> validatedAuthority(value, scheme))
                .orElse(direct.authority());
        var forwardedPort = lastToken(headers, "X-Forwarded-Port").flatMap(TrustedProxyPolicy::port);
        if (forwardedPort.isPresent() && authority.indexOf(':') < 0) {
            var value = forwardedPort.orElseThrow();
            if (!isDefaultPort(scheme, value)) {
                authority = authority + ":" + value;
            }
        }
        return Optional.of(direct.forwarded(client(addresses), scheme, authority));
    }

    private InetAddress client(List<InetAddress> addresses) {
        for (var index = addresses.size() - 1; index >= 0; index--) {
            var address = addresses.get(index);
            if (!isTrusted(address)) {
                return address;
            }
        }
        return addresses.getFirst();
    }

    private boolean isTrusted(InetAddress address) {
        return trusted.stream().anyMatch(cidr -> cidr.contains(address));
    }

    private static List<String> values(Map<String, List<String>> headers, String name) {
        var result = new ArrayList<String>();
        headers.forEach((header, entries) -> {
            if (header.equalsIgnoreCase(name)) {
                result.addAll(entries);
            }
        });
        return List.copyOf(result);
    }

    private static Optional<String> lastToken(Map<String, List<String>> headers, String name) {
        var values = values(headers, name);
        var joined = boundedJoin(values);
        if (joined == null) {
            return Optional.empty();
        }
        var tokens = split(joined, ',');
        return tokens.isEmpty() || tokens.size() > MAX_HOPS
                ? Optional.empty()
                : Optional.of(tokens.getLast().strip());
    }

    private static String boundedJoin(List<String> values) {
        if (values.isEmpty() || values.stream().anyMatch(Objects::isNull)) {
            return null;
        }
        var joined = String.join(",", values);
        return joined.length() <= MAX_HEADER_LENGTH ? joined : null;
    }

    private static List<String> split(String value, char delimiter) {
        var result = new ArrayList<String>();
        var start = 0;
        var quoted = false;
        var escaped = false;
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (character == '\\' && quoted) {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (character == delimiter && !quoted) {
                var token = value.substring(start, index).strip();
                if (token.isEmpty()) {
                    return List.of();
                }
                result.add(token);
                start = index + 1;
            }
        }
        if (quoted || escaped) {
            return List.of();
        }
        var token = value.substring(start).strip();
        if (token.isEmpty()) {
            return List.of();
        }
        result.add(token);
        return List.copyOf(result);
    }

    private static Map<String, String> parameters(String element) {
        var result = new LinkedHashMap<String, String>();
        for (var parameter : split(element, ';')) {
            var separator = parameter.indexOf('=');
            if (separator <= 0 || separator == parameter.length() - 1) {
                return null;
            }
            var name = parameter.substring(0, separator).strip().toLowerCase(Locale.ROOT);
            var value = unquote(parameter.substring(separator + 1).strip());
            if (!name.matches("[a-z][a-z0-9_-]*") || value == null || result.putIfAbsent(name, value) != null) {
                return null;
            }
        }
        return result;
    }

    private static String unquote(String value) {
        if (!value.startsWith("\"")) {
            return value.indexOf('"') >= 0 || value.indexOf('\\') >= 0 ? null : value;
        }
        if (value.length() < 2 || !value.endsWith("\"")) {
            return null;
        }
        var result = new StringBuilder();
        var escaped = false;
        for (var index = 1; index < value.length() - 1; index++) {
            var character = value.charAt(index);
            if (escaped) {
                result.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"' || Character.isISOControl(character)) {
                return null;
            } else {
                result.append(character);
            }
        }
        return escaped ? null : result.toString();
    }

    private static Optional<InetAddress> address(String token) {
        if (token == null) {
            return Optional.empty();
        }
        var value = unquote(token.strip());
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("unknown")
                || value.startsWith("_") || value.indexOf('%') >= 0) {
            return Optional.empty();
        }
        if (value.startsWith("[")) {
            var end = value.indexOf(']');
            if (end < 0 || (end + 1 < value.length() && !validPortSuffix(value.substring(end + 1)))) {
                return Optional.empty();
            }
            value = value.substring(1, end);
        } else if (value.chars().filter(character -> character == ':').count() == 1) {
            var separator = value.lastIndexOf(':');
            if (validPortSuffix(value.substring(separator))) {
                value = value.substring(0, separator);
            }
        }
        try {
            return Optional.of(InetAddress.ofLiteral(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static boolean validPortSuffix(String suffix) {
        return suffix.matches(":(?:[1-9][0-9]{0,4})")
                && Integer.parseInt(suffix.substring(1)) <= 65_535;
    }

    private static Optional<String> validatedScheme(String value) {
        if (value == null) {
            return Optional.empty();
        }
        var normalized = value.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("http") || normalized.equals("https")
                ? Optional.of(normalized)
                : Optional.empty();
    }

    private static Optional<String> validatedAuthority(String value, String scheme) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ClientConnection.unknown(scheme, unquote(value.strip())).authority());
        } catch (IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> port(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,4}")) {
            return Optional.empty();
        }
        var parsed = Integer.parseInt(value);
        return parsed <= 65_535 ? Optional.of(parsed) : Optional.empty();
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return scheme.equals("http") && port == 80 || scheme.equals("https") && port == 443;
    }

    private record Cidr(byte[] network, int prefix) {
        private Cidr {
            network = network.clone();
        }

        static Cidr parse(String encoded) {
            Objects.requireNonNull(encoded, "trusted CIDR");
            var parts = encoded.strip().split("/", -1);
            if (parts.length > 2 || parts[0].isEmpty()) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + encoded);
            }
            final InetAddress address;
            try {
                address = InetAddress.ofLiteral(parts[0]);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + encoded, exception);
            }
            var bytes = address.getAddress();
            var bits = bytes.length * 8;
            final int prefix;
            try {
                prefix = parts.length == 1 ? bits : Integer.parseInt(parts[1]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + encoded, exception);
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + encoded);
            }
            var network = bytes.clone();
            for (var bit = prefix; bit < bits; bit++) {
                network[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
            }
            return new Cidr(network, prefix);
        }

        boolean contains(InetAddress address) {
            var candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            var wholeBytes = prefix / 8;
            if (!Arrays.equals(network, 0, wholeBytes, candidate, 0, wholeBytes)) {
                return false;
            }
            var remaining = prefix % 8;
            if (remaining == 0) {
                return true;
            }
            var mask = 0xff << (8 - remaining);
            return (network[wholeBytes] & mask) == (candidate[wholeBytes] & mask);
        }

        @Override
        public byte[] network() {
            return network.clone();
        }
    }
}
