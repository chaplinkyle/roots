package dev.roots;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable, deliberately low-cardinality completion data for one HTTP exchange.
 * It never includes query values, headers, bodies, form values, cookies, or credentials.
 *
 * @param traceContext request trace identifiers
 * @param startedAt wall-clock request start
 * @param duration monotonic elapsed duration
 * @param method normalized HTTP method
 * @param path logical application path when known
 * @param transportPath physical HTTP endpoint path
 * @param status committed HTTP status, or zero when aborted before commit
 * @param responseBytes response-body bytes successfully written, or {@code -1} when unknown
 */
public record RequestObservation(
        TraceContext traceContext,
        Instant startedAt,
        Duration duration,
        String method,
        String path,
        String transportPath,
        int status,
        long responseBytes
) {
    /** Validates and normalizes observation data. */
    public RequestObservation {
        traceContext = Objects.requireNonNull(traceContext, "traceContext");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        duration = Objects.requireNonNull(duration, "duration");
        method = Objects.requireNonNull(method, "method").toUpperCase(Locale.ROOT);
        path = requirePath(path, "path");
        transportPath = requirePath(transportPath, "transportPath");
        if (duration.isNegative() || duration.compareTo(Duration.ofNanos(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Request duration must fit in nonnegative nanoseconds");
        }
        if (status != 0 && (status < 100 || status > 599)) {
            throw new IllegalArgumentException("Request status must be zero or between 100 and 599");
        }
        if (responseBytes < -1) {
            throw new IllegalArgumentException("Response bytes must be -1 or nonnegative");
        }
    }

    /** Returns the bounded result classification.
     * @return request outcome
     */
    public RequestOutcome outcome() {
        return RequestOutcome.fromStatus(status);
    }

    /** Encodes this event as one stable JSON object suitable for structured logs.
     * @return JSON event without request-sensitive values
     */
    public String json() {
        return "{\"event\":\"roots.request\",\"startedAt\":" + jsonString(startedAt.toString())
                + ",\"traceId\":" + jsonString(traceContext.traceId())
                + ",\"spanId\":" + jsonString(traceContext.spanId())
                + ",\"parentSpanId\":" + traceContext.parentSpanId().map(RequestObservation::jsonString).orElse("null")
                + ",\"sampled\":" + traceContext.sampled()
                + ",\"method\":" + jsonString(method)
                + ",\"path\":" + jsonString(path)
                + ",\"transportPath\":" + jsonString(transportPath)
                + ",\"status\":" + status
                + ",\"outcome\":" + jsonString(outcome().name().toLowerCase(Locale.ROOT))
                + ",\"durationNanos\":" + duration.toNanos()
                + ",\"responseBytes\":" + responseBytes + "}";
    }

    private static String requirePath(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.startsWith("/") || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be an absolute path without control characters");
        }
        return value;
    }

    private static String jsonString(String value) {
        var result = new StringBuilder(value.length() + 8).append('"');
        for (var character : value.toCharArray()) {
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append("\\u%04x".formatted((int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }
}
