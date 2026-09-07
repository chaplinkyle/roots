package com.chaplin.roots;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyPolicyTest {
    @Test
    void ignoresForwardingHeadersUnlessTheDirectPeerIsTrusted() {
        var direct = direct("192.0.2.20");
        var headers = Map.of(
                "Forwarded", List.of("for=203.0.113.9;proto=https;host=public.example"),
                "X-Forwarded-For", List.of("198.51.100.5")
        );

        assertEquals(direct, ProxyPolicy.directOnly().resolve(direct, headers));
        assertEquals(direct, ProxyPolicy.trusted("10.0.0.0/8").resolve(direct, headers));
    }

    @Test
    void resolvesStandardForwardedChainFromTheNearestTrustedSide() {
        var direct = direct("10.0.0.8");
        var resolved = ProxyPolicy.trusted("10.0.0.0/8", "2001:db8:1::/48").resolve(direct, Map.of(
                "Forwarded", List.of(
                        "for=198.51.100.9;proto=http;host=spoofed.example, "
                                + "for=203.0.113.7, for=10.2.3.4;proto=https;host=public.example:8443"
                )
        ));

        assertEquals("203.0.113.7", resolved.clientAddressText());
        assertEquals("https", resolved.scheme());
        assertEquals("public.example:8443", resolved.authority());
        assertEquals("https://public.example:8443", resolved.origin().toString());
        assertTrue(resolved.forwarded());
    }

    @Test
    void supportsQuotedIpv6AndLegacyHeadersWithoutDnsResolution() {
        var direct = direct("2001:db8:1::10");
        var policy = ProxyPolicy.trusted("2001:db8:1::/48");

        var standard = policy.resolve(direct, Map.of(
                "forwarded", List.of("for=\"[2001:db8:ffff::4]:443\";proto=https;host=\"[2001:db8::8]:9443\"")
        ));
        assertEquals("2001:db8:ffff:0:0:0:0:4", standard.clientAddressText());
        assertEquals("[2001:db8::8]:9443", standard.authority());

        var legacy = policy.resolve(direct, Map.of(
                "X-Forwarded-For", List.of("198.51.100.12, 2001:db8:1::20"),
                "X-Forwarded-Proto", List.of("http, https"),
                "X-Forwarded-Host", List.of("internal, app.example"),
                "X-Forwarded-Port", List.of("443")
        ));
        assertEquals("198.51.100.12", legacy.clientAddressText());
        assertEquals("https", legacy.scheme());
        assertEquals("app.example", legacy.authority());
    }

    @Test
    void rejectsMalformedOrAmbiguousForwardingMetadataAsAUnit() {
        var direct = direct("10.0.0.8");
        var policy = ProxyPolicy.trusted("10.0.0.0/8");

        assertEquals(direct, policy.resolve(direct, Map.of("Forwarded", List.of("for=unknown;proto=https"))));
        assertEquals(direct, policy.resolve(direct, Map.of("Forwarded", List.of("for=client.example"))));
        assertEquals(direct, policy.resolve(direct, Map.of("Forwarded", List.of("for=198.51.100.1,,for=10.0.0.2"))));
        assertEquals(direct, policy.resolve(direct, Map.of(
                "Forwarded", List.of("for=198.51.100.1;for=203.0.113.2"),
                "X-Forwarded-For", List.of("192.0.2.9")
        )));
        assertEquals(direct, policy.resolve(direct, Map.of(
                "X-Forwarded-For", List.of("198.51.100.1,not-an-address")
        )));
        assertEquals(direct, policy.resolve(direct, Map.of(
                "Forwarded", List.of("for=198.51.100.1".repeat(600))
        )));
    }

    @Test
    void validatesConnectionAndTrustedRanges() throws Exception {
        var unknown = ClientConnection.unknown("HTTPS", "example.test");
        assertTrue(unknown.clientAddress().isEmpty());
        assertEquals("unknown", unknown.clientAddressText());
        assertFalse(unknown.forwarded());

        assertThrows(IllegalArgumentException.class,
                () -> new ClientConnection(java.util.Optional.of(InetAddress.ofLiteral("127.0.0.1")),
                        "ftp", "example.test", false));
        assertThrows(IllegalArgumentException.class, () -> ClientConnection.unknown("http", "bad host"));
        assertThrows(IllegalArgumentException.class, () -> ClientConnection.unknown("http", "user@example.test"));
        assertThrows(IllegalArgumentException.class, () -> ProxyPolicy.trusted("10.0.0.0/99"));
        assertThrows(IllegalArgumentException.class, () -> ProxyPolicy.trusted("localhost/32"));
        assertThrows(NullPointerException.class, () -> ProxyPolicy.trusted((List<String>) null));
    }

    private static ClientConnection direct(String address) {
        return ClientConnection.direct(InetAddress.ofLiteral(address), "http", "internal:8080");
    }
}
