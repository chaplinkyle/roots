package com.chaplin.roots.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.chaplin.roots.AuthenticatedIdentity;
import com.chaplin.roots.ClientConnection;
import com.chaplin.roots.TraceContext;

/**
 * Minimal transport boundary between the Roots request runtime and an HTTP server adapter.
 * This public internal type exists for separately packaged transport modules, not applications.
 */
public interface TransportExchange {
    /**
     * Returns the HTTP method.
     * @return request method
     */
    String method();

    /**
     * Returns the raw request URI.
     * @return request URI
     */
    URI uri();

    /**
     * Returns all immutable request headers.
     * @return repeated headers
     */
    Map<String, List<String>> requestHeaders();

    /** Finds the first request header case-insensitively.
     * @param name header name
     * @return first value, or {@code null} */
    String requestHeader(String name);

    /** Returns authentication resolved by the transport before runtime dispatch.
     * @return authenticated identity, or empty for anonymous */
    default Optional<AuthenticatedIdentity> identity() {
        return Optional.empty();
    }

    /** Returns the trace context assigned at the runtime boundary.
     * Raw adapters normally inherit the default; the runtime wrapper overrides it.
     * @return current request trace context
     */
    default TraceContext traceContext() {
        return TraceContext.create();
    }

    /** Returns the external path prefix removed by the transport before Roots routing.
     * The empty string represents an application mounted at the origin root.
     * @return normalized external mount path
     */
    default String mountPath() {
        return "";
    }

    /** Returns direct peer and request-origin details before proxy resolution.
     * Custom transports should override this whenever peer information is available.
     * @return direct connection details
     */
    default ClientConnection connection() {
        return ClientConnection.unknown("http", "localhost");
    }

    /**
     * Opens the request body.
     * @return request body stream
     * @throws IOException when the request body cannot be opened
     */
    InputStream requestBody() throws IOException;

    /** Replaces a response header.
     * @param name header name
     * @param values repeated values */
    void responseHeader(String name, List<String> values);

    /** Replaces a response header with one value.
     * @param name header name
     * @param value header value */
    default void responseHeader(String name, String value) {
        responseHeader(name, List.of(value));
    }

    /** Commits response status and framing.
     * A length of {@code -1} suppresses a body, {@code 0} selects streaming framing,
     * and a positive value declares a fixed body length.
     * @param status HTTP status
     * @param length framing length
     * @throws IOException when the response cannot be committed */
    void sendResponseHeaders(int status, long length) throws IOException;

    /**
     * Opens the committed response body.
     * @return response stream
     * @throws IOException when the response body cannot be opened
     */
    OutputStream responseBody() throws IOException;

    /** Closes or completes the exchange. */
    void close();
}
