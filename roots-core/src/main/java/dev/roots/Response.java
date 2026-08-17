package dev.roots;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * An immutable buffered or lazily streamed HTTP response.
 *
 * <p>Streaming writers are not invoked for {@code HEAD} requests. A writer should
 * acquire and release application resources inside its one synchronous invocation;
 * constructing or copying a response never opens the source.</p>
 */
public final class Response {
    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final ResponseBodyWriter bodyWriter;
    private final long contentLength;

    /** Creates an immutable buffered response from defensively copied bytes.
     * @param status HTTP status code
     * @param headers repeated response headers
     * @param body raw response body */
    public Response(int status, Map<String, List<String>> headers, byte[] body) {
        this(status, headers, Objects.requireNonNull(body, "body").clone(), null, body.length);
    }

    private Response(
            int status,
            Map<String, List<String>> headers,
            byte[] body,
            ResponseBodyWriter bodyWriter,
            long contentLength
    ) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("HTTP status must be between 100 and 599");
        }
        if (contentLength < -1) {
            throw new IllegalArgumentException("Response content length must be non-negative or unknown");
        }
        this.status = status;
        this.headers = copyHeaders(headers);
        this.body = body;
        this.bodyWriter = bodyWriter;
        this.contentLength = contentLength;
        validateDeclaredContentLength(this.headers, contentLength);
    }

    /** Returns the HTTP status.
     * @return status code */
    public int status() {
        return status;
    }

    /** Returns immutable repeated response headers.
     * @return headers */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * Returns a defensive copy of a buffered response body.
     *
     * @return copied body bytes
     * @throws IllegalStateException for a streaming response
     */
    public byte[] body() {
        if (streaming()) {
            throw new IllegalStateException("A streaming response has no buffered body array");
        }
        return body.clone();
    }

    /** Decodes a buffered response body as UTF-8.
     * @return decoded body
     * @throws IllegalStateException for a streaming response */
    public String bodyText() {
        return new String(body(), StandardCharsets.UTF_8);
    }

    /** Reports whether the body is produced lazily.
     * @return true for a streaming response */
    public boolean streaming() {
        return bodyWriter != null;
    }

    /** Returns the exact response length when known.
     * Buffered and fixed-length streaming responses always have a value.
     * @return exact length or empty for chunked streaming */
    public OptionalLong contentLength() {
        return contentLength < 0 ? OptionalLong.empty() : OptionalLong.of(contentLength);
    }

    /**
     * Writes the complete buffered or streaming body without closing the destination.
     * Fixed-length streaming writers are checked for both underflow and overflow.
     *
     * @param output destination stream
     * @throws IOException when production or transfer fails
     */
    public void transferTo(OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output");
        if (!streaming()) {
            output.write(body);
            return;
        }
        if (contentLength < 0) {
            bodyWriter.writeTo(new NonClosingOutputStream(output));
            return;
        }
        var exact = new ExactLengthOutputStream(output, contentLength);
        bodyWriter.writeTo(exact);
        exact.requireComplete();
    }

    /** Creates a fixed-length lazy response.
     * @param status HTTP status
     * @param contentType response content type
     * @param contentLength exact byte count
     * @param writer lazy synchronous body writer
     * @return streaming response */
    public static Response stream(
            int status,
            String contentType,
            long contentLength,
            ResponseBodyWriter writer
    ) {
        requireBodyStatus(status);
        if (contentLength < 0) {
            throw new IllegalArgumentException("Fixed response content length must be non-negative");
        }
        return new Response(
                status,
                Map.of("Content-Type", List.of(Objects.requireNonNull(contentType, "contentType"))),
                null,
                Objects.requireNonNull(writer, "writer"),
                contentLength
        );
    }

    /** Creates an unknown-length lazy response using transport streaming framing.
     * @param status HTTP status
     * @param contentType response content type
     * @param writer lazy synchronous body writer
     * @return streaming response */
    public static Response stream(int status, String contentType, ResponseBodyWriter writer) {
        requireBodyStatus(status);
        return new Response(
                status,
                Map.of("Content-Type", List.of(Objects.requireNonNull(contentType, "contentType"))),
                null,
                Objects.requireNonNull(writer, "writer"),
                -1
        );
    }

    /**
     * Creates a lazy fixed-length file response. The path is opened only when the
     * body is sent and the resulting stream is closed after transfer.
     *
     * @param status HTTP status
     * @param contentType response content type
     * @param path existing regular file
     * @return lazy file response
     * @throws IOException when file metadata cannot be read
     */
    public static Response file(int status, String contentType, Path path) throws IOException {
        var source = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new NoSuchFileException(source.toString());
        }
        var length = Files.size(source);
        return stream(status, contentType, length, output -> {
            try (var input = Files.newInputStream(source)) {
                input.transferTo(output);
            }
        });
    }

    /** Creates a plain-text response.
     * @param status HTTP status
     * @param body response text
     * @return response */
    public static Response text(int status, String body) {
        return of(status, "text/plain; charset=utf-8", body);
    }

    /** Creates an HTML response.
     * @param status HTTP status
     * @param body response markup
     * @return response */
    public static Response html(int status, String body) {
        return of(status, "text/html; charset=utf-8", body);
    }

    /** Creates a JSON response.
     * @param status HTTP status
     * @param body serialized JSON
     * @return response */
    public static Response json(int status, String body) {
        return of(status, "application/json; charset=utf-8", body);
    }

    /** Creates a 204 response.
     * @return no-content response */
    public static Response noContent() {
        return new Response(204, Map.of(), new byte[0]);
    }

    /** Creates a 303 redirect response.
     * @param location redirect location
     * @return redirect response */
    public static Response redirect(String location) {
        return new Response(303, Map.of("Location", List.of(location)), new byte[0]);
    }

    /** Creates a 405 response for an unsupported method.
     * @param method HTTP method
     * @return method-not-allowed response */
    public static Response methodNotAllowed(String method) {
        return text(405, "Method not allowed: " + method);
    }

    /** Creates a UTF-8 response with one content type.
     * @param status HTTP status
     * @param contentType response content type
     * @param body response text
     * @return response */
    public static Response of(int status, String contentType, String body) {
        return new Response(
                status,
                Map.of("Content-Type", List.of(contentType)),
                body.getBytes(StandardCharsets.UTF_8)
        );
    }

    /** Returns a copy with a replaced single-value header.
     * @param name header name
     * @param value header value
     * @return updated response */
    public Response withHeader(String name, String value) {
        validateHeaderName(name);
        validateHeaderValue(value);
        var updated = new LinkedHashMap<>(headers);
        updated.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        updated.put(name, List.of(value));
        return copyWithHeaders(updated);
    }

    /** Returns a copy with one additional header value, preserving existing values case-insensitively.
     * @param name header name
     * @param value header value
     * @return updated response */
    public Response withAddedHeader(String name, String value) {
        validateHeaderName(name);
        validateHeaderValue(value);
        var updated = new LinkedHashMap<String, List<String>>();
        var combined = new java.util.ArrayList<String>();
        headers.forEach((existing, values) -> {
            if (existing.equalsIgnoreCase(name)) combined.addAll(values);
            else updated.put(existing, values);
        });
        combined.add(value);
        updated.put(name, List.copyOf(combined));
        return copyWithHeaders(updated);
    }

    /** Returns a copy with one appended validated {@code Set-Cookie} field.
     * @param cookie response cookie
     * @return updated response */
    public Response withCookie(ResponseCookie cookie) {
        return withAddedHeader("Set-Cookie", Objects.requireNonNull(cookie, "cookie").headerValue());
    }

    private Response copyWithHeaders(Map<String, List<String>> updated) {
        return new Response(status, updated, body, bodyWriter, contentLength);
    }

    private static Map<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
        var copiedHeaders = new LinkedHashMap<String, List<String>>();
        Objects.requireNonNull(headers, "headers").forEach((name, values) -> {
            validateHeaderName(name);
            var copiedValues = List.copyOf(Objects.requireNonNull(values, "header values"));
            copiedValues.forEach(Response::validateHeaderValue);
            copiedHeaders.put(name, copiedValues);
        });
        return Map.copyOf(copiedHeaders);
    }

    private static void validateDeclaredContentLength(Map<String, List<String>> headers, long expected) {
        var values = headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("Content-Length"))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
        if (values.isEmpty()) {
            return;
        }
        if (values.size() != 1 || expected < 0 || !digits(values.getFirst())) {
            throw new IllegalArgumentException("Content-Length must be one exact known response length");
        }
        final long declared;
        try {
            declared = Long.parseLong(values.getFirst());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid Content-Length response header", exception);
        }
        if (declared != expected) {
            throw new IllegalArgumentException("Content-Length does not match the response body");
        }
    }

    private static boolean digits(String value) {
        return !value.isEmpty() && value.chars().allMatch(character -> character >= '0' && character <= '9');
    }

    private static void requireBodyStatus(int status) {
        if (status < 200 || status == 204 || status == 205 || status == 304) {
            throw new IllegalArgumentException("Streaming responses require a body-capable final HTTP status");
        }
    }

    private static void validateHeaderName(String name) {
        Objects.requireNonNull(name, "header name");
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new IllegalArgumentException("Invalid HTTP header name: " + name);
        }
    }

    private static void validateHeaderValue(String value) {
        Objects.requireNonNull(value, "header value");
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("HTTP header values cannot contain newlines");
        }
    }

    @Override
    public final boolean equals(Object candidate) {
        return this == candidate || candidate instanceof Response other
                && status == other.status
                && contentLength == other.contentLength
                && headers.equals(other.headers)
                && Arrays.equals(body, other.body)
                && Objects.equals(bodyWriter, other.bodyWriter);
    }

    @Override
    public final int hashCode() {
        var result = Objects.hash(status, headers, bodyWriter, contentLength);
        return 31 * result + Arrays.hashCode(body);
    }

    @Override
    public final String toString() {
        return "Response[status=" + status + ", headers=" + headers + ", body="
                + (streaming() ? "streaming(" + (contentLength < 0 ? "unknown" : contentLength) + ")"
                : Arrays.toString(body)) + "]";
    }

    private static class NonClosingOutputStream extends OutputStream {
        private final OutputStream delegate;

        private NonClosingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    private static final class ExactLengthOutputStream extends NonClosingOutputStream {
        private final long expected;
        private long written;

        private ExactLengthOutputStream(OutputStream delegate, long expected) {
            super(delegate);
            this.expected = expected;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            super.write(value);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireCapacity(length);
            super.write(bytes, offset, length);
            written += length;
        }

        private void requireCapacity(int length) throws IOException {
            if (length > expected - written) {
                throw new IOException("Streaming response exceeded its declared Content-Length");
            }
        }

        private void requireComplete() throws IOException {
            if (written != expected) {
                throw new IOException("Streaming response ended before its declared Content-Length");
            }
        }
    }
}
