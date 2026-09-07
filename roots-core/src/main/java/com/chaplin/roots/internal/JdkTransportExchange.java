package com.chaplin.roots.internal;

import com.sun.net.httpserver.HttpExchange;
import com.chaplin.roots.ClientConnection;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapts the JDK HTTP server exchange to the transport-neutral Roots runtime. */
final class JdkTransportExchange implements TransportExchange {
    private final HttpExchange exchange;

    JdkTransportExchange(HttpExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public String method() {
        return exchange.getRequestMethod();
    }

    @Override
    public URI uri() {
        return exchange.getRequestURI();
    }

    @Override
    public Map<String, List<String>> requestHeaders() {
        var headers = new LinkedHashMap<String, List<String>>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
        return Map.copyOf(headers);
    }

    @Override
    public String requestHeader(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    @Override
    public ClientConnection connection() {
        var local = exchange.getLocalAddress();
        return ClientConnection.direct(
                exchange.getRemoteAddress().getAddress(),
                "http",
                authority(local.getHostString(), local.getPort())
        );
    }

    @Override
    public InputStream requestBody() {
        return exchange.getRequestBody();
    }

    @Override
    public void responseHeader(String name, List<String> values) {
        exchange.getResponseHeaders().put(name, new ArrayList<>(values));
    }

    @Override
    public void sendResponseHeaders(int status, long length) throws java.io.IOException {
        exchange.sendResponseHeaders(status, length);
    }

    @Override
    public OutputStream responseBody() {
        return exchange.getResponseBody();
    }

    @Override
    public void close() {
        exchange.close();
    }

    private static String authority(String host, int port) {
        var formatted = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        return port == 80 ? formatted : formatted + ":" + port;
    }
}
