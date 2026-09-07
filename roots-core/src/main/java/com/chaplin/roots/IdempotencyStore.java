package com.chaplin.roots;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Scoped replay protection for buffered machine API responses. Authenticate and
 * authorize each request before entering this boundary. Include tenant/principal
 * identity and operation in the scope; never share one global scope across users.
 * A durable implementation must coordinate its receipt and business mutation in
 * the same transaction to survive process loss without duplicate side effects.
 */
public interface IdempotencyStore {
    /**
     * Executes a new key or replays its completed response. A mismatching fingerprint
     * returns 422, in-flight or uncertain work returns 409, and saturation returns 503.
     * Failed work is held as uncertain rather than automatically executed again.
     *
     * @param scope application-defined tenant/principal/operation scope, at most 512 characters
     * @param key opaque client key of 1–200 visible ASCII characters
     * @param fingerprint 64 lowercase hexadecimal SHA-256 characters
     * @param retention retention after completion/failure, from one second to seven days
     * @param work operation producing a buffered response without Set-Cookie
     * @return original, replayed, or conflict/capacity response
     * @throws Exception if the operation fails; its key remains uncertain
     */
    Response execute(String scope, String key, String fingerprint, Duration retention, Work work) throws Exception;

    /** Creates an exact-capacity process-local store. Entries are never evicted early.
     * This implementation does not survive restarts or coordinate multiple JVMs.
     * @param maxEntries maximum active and completed receipts
     * @param maxResponseBytes maximum retained buffered body per receipt
     * @return thread-safe process-local store */
    static IdempotencyStore inMemory(int maxEntries, int maxResponseBytes) {
        return new InMemoryIdempotencyStore(maxEntries, maxResponseBytes, java.time.Clock.systemUTC());
    }

    /**
     * Fingerprints method, logical path, ordered repeated query values, Content-Type,
     * and original body bytes without allocating a body-sized buffer. Credentials
     * are intentionally excluded: identity must be part of the authenticated scope.
     *
     * @param request repeatable original API request
     * @return lowercase SHA-256 fingerprint
     * @throws IOException if request content cannot be read
     */
    static String fingerprint(Request request) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            part(digest, request.method());
            part(digest, request.path());
            digest.update(ByteBuffer.allocate(4).putInt(request.query().size()).array());
            request.query().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
                part(digest, entry.getKey());
                digest.update(ByteBuffer.allocate(4).putInt(entry.getValue().size()).array());
                entry.getValue().forEach(value -> part(digest, value));
            });
            var contentTypes = request.headers().entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("Content-Type"))
                    .flatMap(entry -> entry.getValue().stream()).toList();
            digest.update(ByteBuffer.allocate(4).putInt(contentTypes.size()).array());
            contentTypes.forEach(value -> part(digest, value));
            try (var input = request.bodyStream()) {
                var buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static void part(MessageDigest digest, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    /** One authorized API mutation.
     */
    @FunctionalInterface
    interface Work {
        /** Performs the operation.
         * @return bounded buffered response
         * @throws Exception on failure, including an uncertain outcome */
        Response run() throws Exception;
    }
}
