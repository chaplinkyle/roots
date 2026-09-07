package com.chaplin.roots;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable W3C Trace Context identifiers for one Roots server operation.
 *
 * @param traceId 32-character lowercase trace identifier
 * @param spanId 16-character lowercase identifier for the current operation
 * @param parentSpanId incoming caller span identifier, when the trace was continued
 * @param traceFlags supported W3C trace flags in the low two bits
 */
public record TraceContext(
        String traceId,
        String spanId,
        Optional<String> parentSpanId,
        int traceFlags
) {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SUPPORTED_FLAGS = 0x03;
    private static final int RANDOM_TRACE_ID = 0x02;
    private static final String ZERO_TRACE = "0".repeat(32);
    private static final String ZERO_SPAN = "0".repeat(16);

    /** Validates a trace context. */
    public TraceContext {
        traceId = requireIdentifier(traceId, 32, "traceId");
        spanId = requireIdentifier(spanId, 16, "spanId");
        parentSpanId = Objects.requireNonNull(parentSpanId, "parentSpanId")
                .map(value -> requireIdentifier(value, 16, "parentSpanId"));
        if (traceFlags < 0 || traceFlags > 0xff) {
            throw new IllegalArgumentException("traceFlags must fit in one unsigned byte");
        }
        traceFlags &= SUPPORTED_FLAGS;
    }

    /** Starts a new unsampled trace with a cryptographically random identifier.
     * @return new root trace context
     */
    public static TraceContext create() {
        return create(false);
    }

    /** Starts a new trace with a caller-selected sampling recommendation.
     * @param sampled whether the sampled flag is set
     * @return new root trace context
     */
    public static TraceContext create(boolean sampled) {
        return new TraceContext(randomIdentifier(16), randomIdentifier(8), Optional.empty(),
                RANDOM_TRACE_ID | (sampled ? 1 : 0));
    }

    /**
     * Continues a valid W3C {@code traceparent} value with a fresh server span.
     * Invalid, oversized, or unsupported input safely starts a new trace.
     *
     * @param traceparent incoming header value, or {@code null}
     * @return current server trace context
     */
    public static TraceContext fromTraceparent(String traceparent) {
        if (!validWireShape(traceparent)) {
            return create();
        }
        var traceId = traceparent.substring(3, 35);
        var parentId = traceparent.substring(36, 52);
        if (traceId.equals(ZERO_TRACE) || parentId.equals(ZERO_SPAN)) {
            return create();
        }
        var flags = Integer.parseInt(traceparent.substring(53, 55), 16) & SUPPORTED_FLAGS;
        return new TraceContext(traceId, randomIdentifier(8), Optional.of(parentId), flags);
    }

    /** Creates a child operation whose parent is this context's current span.
     * @return child context
     */
    public TraceContext child() {
        return new TraceContext(traceId, randomIdentifier(8), Optional.of(spanId), traceFlags);
    }

    /** Returns the version-00 W3C value for propagation to another operation.
     * @return serialized traceparent value
     */
    public String traceparent() {
        return "00-" + traceId + "-" + spanId + "-" + "%02x".formatted(traceFlags);
    }

    /** Returns whether the caller recommended sampling.
     * @return sampled flag
     */
    public boolean sampled() {
        return (traceFlags & 0x01) != 0;
    }

    /** Returns whether the trace identifier carries the W3C random-ID guarantee.
     * @return random trace-ID flag
     */
    public boolean randomTraceId() {
        return (traceFlags & RANDOM_TRACE_ID) != 0;
    }

    private static boolean validWireShape(String value) {
        if (value == null || value.length() < 55 || value.length() > 512
                || value.charAt(2) != '-' || value.charAt(35) != '-' || value.charAt(52) != '-') {
            return false;
        }
        var version = value.substring(0, 2);
        if (!lowerHex(version) || version.equals("ff")) {
            return false;
        }
        if (version.equals("00") ? value.length() != 55 : value.length() > 55 && value.charAt(55) != '-') {
            return false;
        }
        return lowerHex(value.substring(3, 35))
                && lowerHex(value.substring(36, 52))
                && lowerHex(value.substring(53, 55));
    }

    private static String requireIdentifier(String value, int length, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() != length || !lowerHex(value) || value.chars().allMatch(character -> character == '0')) {
            throw new IllegalArgumentException(name + " must be a nonzero " + length
                    + "-character lowercase hexadecimal value");
        }
        return value;
    }

    private static boolean lowerHex(String value) {
        return value.chars().allMatch(character -> character >= '0' && character <= '9'
                || character >= 'a' && character <= 'f');
    }

    private static String randomIdentifier(int bytes) {
        var value = new byte[bytes];
        do {
            RANDOM.nextBytes(value);
        } while (allZero(value));
        return HexFormat.of().formatHex(value);
    }

    private static boolean allZero(byte[] value) {
        for (var current : value) {
            if (current != 0) {
                return false;
            }
        }
        return true;
    }
}
