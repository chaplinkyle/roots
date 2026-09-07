package com.chaplin.roots;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WebhookVerifierTest {
    private static final String SECRET = "whsec_MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="; // gitleaks:allow -- deterministic test vector: repeating decimal digits, not a service credential
    // Independently computed using Node.js crypto.createHmac, not the verifier.
    private static final String SIGNATURE = "v1,RgB2j1WlAVkPv53EWzvFu3n+3K9gP6TtX4HJ5k6AWIs=";
    private static final String BODY = "{\"hello\":\"world\"}";
    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);

    private WebhookVerifier verifier() {
        return new WebhookVerifier(List.of(SECRET), Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void verifiesAnIndependentVectorAndLeavesBodyRepeatable() throws Exception {
        try (var request = request("msg_123", "1700000000", SIGNATURE, BODY)) {
            var result = verifier().verify(request).orElseThrow();
            assertEquals("msg_123", result.id());
            assertEquals(NOW, result.timestamp());
            assertEquals(BODY, new String(request.bodyStream().readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(verifier().verify(request).isPresent(), "Signature validation is separate from deduplication");
        }
    }

    @Test
    void rejectsAlteredPayloadMetadataAndExpiredOrFutureCredentials() throws Exception {
        assertTrue(verifier().verify(request("msg_123", "1700000000", SIGNATURE, BODY + " ")).isEmpty());
        assertTrue(verifier().verify(request("msg_other", "1700000000", SIGNATURE, BODY)).isEmpty());
        assertTrue(verifier().verify(request("msg_123", "1700000001", SIGNATURE, BODY)).isEmpty());
        assertTrue(verifier().verify(request("msg.123", "1700000000", SIGNATURE, BODY)).isEmpty());
        assertTrue(verifier().verify(request("msg_123", "-1", SIGNATURE, BODY)).isEmpty());
        assertTrue(verifier().verify(request("msg_123", "01700000000", SIGNATURE, BODY)).isEmpty());
        for (var offset : new int[]{-301, 301}) {
            var verifier = new WebhookVerifier(List.of(SECRET), Duration.ofMinutes(5),
                    Clock.fixed(NOW.plusSeconds(offset), ZoneOffset.UTC));
            assertTrue(verifier.verify(request("msg_123", "1700000000", SIGNATURE, BODY)).isEmpty());
        }
    }

    @Test
    void supportsBoundedKeyRotationAndIgnoresUnsupportedVersions() throws Exception {
        var keys = new ArrayList<>(List.of("whsec_" + "AQEB".repeat(8), SECRET));
        var verifier = new WebhookVerifier(keys, Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
        keys.clear();
        assertTrue(verifier.verify(request("msg_123", "1700000000", "v1a,unsupported " + SIGNATURE, BODY)).isPresent());
        assertTrue(verifier.verify(request("msg_123", "1700000000", "v1,!!! " + SIGNATURE, BODY)).isPresent());
        assertTrue(verifier.verify(request("msg_123", "1700000000", "v1a,unsupported", BODY)).isEmpty());
        assertTrue(verifier.verify(request("msg_123", "1700000000", (SIGNATURE + " ").repeat(9), BODY)).isEmpty());
        assertTrue(verifier.verify(request("msg_123", "1700000000", "v1,AQ==", BODY)).isEmpty());
    }

    @Test
    void rejectsDuplicateHeadersAndNonPostMethods() throws Exception {
        var headers = Map.of("webhook-id", List.of("msg_123"), "Webhook-Id", List.of("msg_other"),
                "webhook-timestamp", List.of("1700000000"), "webhook-signature", List.of(SIGNATURE));
        var request = new Request("POST", "/webhook", Map.of(), Map.of(), headers, Map.of(),
                BODY.getBytes(StandardCharsets.UTF_8), new Session("webhook"));
        assertTrue(verifier().verify(request).isEmpty());
        assertTrue(verifier().verify(new Request("GET", "/webhook", Map.of(), Map.of(), Map.of(),
                Map.of(), new byte[0], new Session("webhook"))).isEmpty());
    }

    @Test
    void rejectsWeakKeysAndUnboundedToleranceWithoutPrintingSecrets() {
        for (var secret : List.of("incorrect-prefix", "whsec_!!!", "whsec_AQ==")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> new WebhookVerifier(List.of(secret), Duration.ofMinutes(5)));
            assertFalse(failure.toString().contains(secret));
        }
        assertThrows(IllegalArgumentException.class, () -> new WebhookVerifier(List.of(), Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> new WebhookVerifier(List.of(SECRET), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new WebhookVerifier(List.of(SECRET), Duration.ofDays(1)));
    }

    private Request request(String id, String timestamp, String signature, String body) {
        return new Request("POST", "/webhook", Map.of(), Map.of(), Map.of(
                "webhook-id", List.of(id), "webhook-timestamp", List.of(timestamp),
                "webhook-signature", List.of(signature)), Map.of(), body.getBytes(StandardCharsets.UTF_8),
                new Session("webhook"));
    }
}
