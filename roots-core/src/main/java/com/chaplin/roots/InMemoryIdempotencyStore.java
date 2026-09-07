package com.chaplin.roots;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

final class InMemoryIdempotencyStore implements IdempotencyStore {
    private final int maxEntries;
    private final int maxResponseBytes;
    private final Clock clock;
    private final Map<Key, Entry> entries = new HashMap<>();
    private final PriorityQueue<Expiry> expiry = new PriorityQueue<>(java.util.Comparator.comparing(Expiry::at));

    InMemoryIdempotencyStore(int maxEntries, int maxResponseBytes, Clock clock) {
        if (maxEntries < 1 || maxResponseBytes < 1) throw new IllegalArgumentException("Idempotency limits must be positive");
        this.maxEntries = maxEntries;
        this.maxResponseBytes = maxResponseBytes;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Response execute(String scope, String key, String fingerprint, Duration retention, Work work) throws Exception {
        validate(scope, key, fingerprint, retention);
        Objects.requireNonNull(work, "work");
        var receipt = new Key(scope, key);
        final Entry entry;
        synchronized (entries) {
            expire(clock.instant());
            var existing = entries.get(receipt);
            if (existing != null) {
                if (!existing.fingerprint.equals(fingerprint)) {
                    return problem(422, "key-reused", "Idempotency key reused", "Use a new key for a different request.");
                }
                if (existing.response != null) return existing.response.withHeader("Idempotency-Replayed", "true");
                return problem(409, existing.uncertain ? "outcome-unknown" : "operation-in-progress",
                        existing.uncertain ? "Operation outcome unknown" : "Operation in progress",
                        existing.uncertain ? "Check the operation status before attempting another mutation."
                                : "An operation with this key is already running.");
            }
            if (entries.size() >= maxEntries) {
                return problem(503, "receipt-capacity", "Idempotency capacity exhausted",
                        "Retry later using the same key.").withHeader("Retry-After", "1");
            }
            entry = new Entry(fingerprint);
            entries.put(receipt, entry);
        }
        try {
            var response = Objects.requireNonNull(work.run(), "idempotent response");
            validateResponse(response);
            finish(receipt, entry, response, retention);
            return response.withHeader("Idempotency-Replayed", "false");
        } catch (Exception | Error failure) {
            finish(receipt, entry, null, retention);
            throw failure;
        }
    }

    private void finish(Key key, Entry entry, Response response, Duration retention) {
        synchronized (entries) {
            entry.response = response;
            entry.uncertain = response == null;
            expiry.add(new Expiry(clock.instant().plus(retention), key, entry));
        }
    }

    private void expire(Instant now) {
        while (!expiry.isEmpty() && !expiry.peek().at().isAfter(now)) {
            var expired = expiry.remove();
            entries.remove(expired.key(), expired.entry());
        }
    }

    private void validateResponse(Response response) {
        if (response.streaming() || response.contentLength().orElseThrow() > maxResponseBytes) {
            throw new IllegalArgumentException("Idempotent responses must be buffered within the configured byte limit");
        }
        long headerSize = 0;
        int headerValues = 0;
        if (response.headers().size() > 64) throw new IllegalArgumentException("Too many idempotent response headers");
        for (var header : response.headers().entrySet()) {
            if (header.getKey().equalsIgnoreCase("Set-Cookie")) {
                throw new IllegalArgumentException("Idempotent responses must not replay cookies");
            }
            headerSize += header.getKey().length();
            headerValues += header.getValue().size();
            if (headerValues > 128) throw new IllegalArgumentException("Too many idempotent response header values");
            for (var value : header.getValue()) headerSize += value.length();
        }
        if (headerSize > 16384) throw new IllegalArgumentException("Idempotent response headers exceed 16 KiB");
    }

    private static void validate(String scope, String key, String fingerprint, Duration retention) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(retention, "retention");
        if (scope.isBlank() || scope.length() > 512 || scope.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Idempotency scope must be printable and at most 512 characters");
        }
        if (key.isEmpty() || key.length() > 200 || key.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new IllegalArgumentException("Idempotency key must contain 1 to 200 visible ASCII characters");
        }
        if (!fingerprint.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Idempotency fingerprint must be SHA-256");
        if (retention.compareTo(Duration.ofSeconds(1)) < 0 || retention.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Idempotency retention must be between one second and seven days");
        }
    }

    private static Response problem(int status, String code, String title, String detail) {
        return ProblemDetail.of(URI.create("urn:roots:problem:" + code), status, title, detail).response();
    }

    private record Key(String scope, String key) { }
    private record Expiry(Instant at, Key key, Entry entry) { }
    private static final class Entry {
        private final String fingerprint;
        private Response response;
        private boolean uncertain;
        private Entry(String fingerprint) { this.fingerprint = fingerprint; }
    }
}
