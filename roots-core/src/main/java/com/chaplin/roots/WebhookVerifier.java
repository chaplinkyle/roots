package com.chaplin.roots;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Standard Webhooks v1 (HMAC-SHA256) verification over the original request bytes.
 * Immutable and thread-safe, with bounded metadata and application-configured key
 * rotation. Verification does not deduplicate deliveries: persist the verified ID
 * with the business mutation in one transaction before acknowledging a webhook.
 */
public final class WebhookVerifier {
    private final List<SecretKeySpec> keys;
    private final Duration tolerance;
    private final Clock clock;

    /** Creates a verifier using the UTC system clock.
     * @param secrets one to four {@code whsec_} base64 secrets, each 24–64 decoded bytes
     * @param tolerance allowable past/future timestamp skew, from one second to one hour
     */
    public WebhookVerifier(List<String> secrets, Duration tolerance) {
        this(secrets, tolerance, Clock.systemUTC());
    }

    /** Creates a verifier with an explicit clock.
     * @param secrets configured endpoint secrets; never taken from request metadata
     * @param tolerance allowable timestamp skew, from one second to one hour
     * @param clock clock used for timestamp validation
     */
    public WebhookVerifier(List<String> secrets, Duration tolerance, Clock clock) {
        Objects.requireNonNull(secrets, "secrets");
        this.tolerance = Objects.requireNonNull(tolerance, "tolerance");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (secrets.isEmpty() || secrets.size() > 4) throw new IllegalArgumentException("Configure one to four webhook secrets");
        if (tolerance.compareTo(Duration.ofSeconds(1)) < 0 || tolerance.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Webhook tolerance must be between one second and one hour");
        }
        var decoded = new ArrayList<SecretKeySpec>();
        for (var secret : secrets) {
            if (secret == null || !secret.startsWith("whsec_") || secret.length() > 94) {
                throw new IllegalArgumentException("Invalid webhook secret format");
            }
            final byte[] bytes;
            try { bytes = Base64.getDecoder().decode(secret.substring(6)); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid webhook secret encoding"); }
            if (bytes.length < 24 || bytes.length > 64) throw new IllegalArgumentException("Webhook secret must contain 24 to 64 bytes");
            decoded.add(new SecretKeySpec(bytes, "HmacSHA256"));
            java.util.Arrays.fill(bytes, (byte) 0);
        }
        keys = List.copyOf(decoded);
    }

    /**
     * Verifies a POST delivery using one unambiguous copy of each Standard Webhooks
     * header. Unsupported signature versions are ignored. Request content is streamed
     * once into the configured MACs and remains repeatable for application parsing.
     *
     * @param request original request before any decoding or body transformation
     * @return verified ID and timestamp, or empty for malformed, stale, or invalid credentials
     * @throws IOException if the original body cannot be read
     */
    public Optional<VerifiedWebhook> verify(Request request) throws IOException {
        Objects.requireNonNull(request, "request");
        if (!request.method().equals("POST")) return Optional.empty();
        var id = singleHeader(request, "webhook-id", 256);
        var timestamp = singleHeader(request, "webhook-timestamp", 12);
        var signatures = singleHeader(request, "webhook-signature", 4096);
        if (id == null || !id.matches("[A-Za-z0-9_:-]{1,256}")
                || timestamp == null || !timestamp.matches("0|[1-9][0-9]{0,11}") || signatures == null) {
            return Optional.empty();
        }
        var sent = Instant.ofEpochSecond(Long.parseLong(timestamp));
        if (Duration.between(sent, clock.instant()).abs().compareTo(tolerance) > 0) return Optional.empty();
        var tokens = signatures.split(" ", -1);
        if (tokens.length > 8) return Optional.empty();
        var candidates = new ArrayList<byte[]>();
        for (var token : tokens) {
            if (!token.startsWith("v1,")) continue;
            try {
                var signature = Base64.getDecoder().decode(token.substring(3));
                if (signature.length == 32) candidates.add(signature);
            } catch (IllegalArgumentException malformed) {
                // An unrelated invalid rotation signature cannot authenticate a request.
            }
        }
        if (candidates.isEmpty()) return Optional.empty();
        try {
            var macs = new ArrayList<Mac>();
            var prefix = (id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8);
            for (var key : keys) {
                var mac = Mac.getInstance("HmacSHA256");
                mac.init(key);
                mac.update(prefix);
                macs.add(mac);
            }
            try (var input = request.bodyStream()) {
                var buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    for (var mac : macs) mac.update(buffer, 0, read);
                }
            }
            var valid = false;
            for (var mac : macs) {
                var expected = mac.doFinal();
                for (var candidate : candidates) valid |= MessageDigest.isEqual(expected, candidate);
            }
            return valid ? Optional.of(new VerifiedWebhook(id, sent)) : Optional.empty();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
        }
    }

    private static String singleHeader(Request request, String name, int maximum) {
        var values = request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream()).toList();
        return values.size() == 1 && values.getFirst().length() <= maximum ? values.getFirst() : null;
    }

    /** Authenticated delivery metadata. The ID must still be deduplicated transactionally.
     * @param id authenticated event identifier
     * @param timestamp authenticated sender timestamp */
    public record VerifiedWebhook(String id, Instant timestamp) {
        /** Requires non-null verified metadata. */
        public VerifiedWebhook {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }
}
