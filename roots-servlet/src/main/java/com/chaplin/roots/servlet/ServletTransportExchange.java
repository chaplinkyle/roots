package com.chaplin.roots.servlet;

import com.chaplin.roots.AuthenticatedIdentity;
import com.chaplin.roots.ClientConnection;
import com.chaplin.roots.internal.TransportExchange;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

final class ServletTransportExchange implements TransportExchange {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final AsyncContext asynchronous;
    private final Optional<AuthenticatedIdentity> identity;
    private final AtomicBoolean closed = new AtomicBoolean();

    ServletTransportExchange(
            HttpServletRequest request,
            HttpServletResponse response,
            AsyncContext asynchronous,
            Optional<AuthenticatedIdentity> identity
    ) {
        this.request = Objects.requireNonNull(request, "request");
        this.response = Objects.requireNonNull(response, "response");
        this.asynchronous = asynchronous;
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    void startAsyncWork(Runnable task) {
        var worker = Thread.ofVirtual().unstarted(() -> {
            if (!closed.get()) task.run();
        });
        asynchronous.addListener(new AsyncListener() {
            private void interruptWorker() {
                if (Thread.currentThread() != worker) worker.interrupt();
            }
            @Override public void onComplete(AsyncEvent event) {
                closed.set(true);
                interruptWorker();
            }
            @Override public void onError(AsyncEvent event) {
                interruptWorker();
                close();
            }
            @Override public void onTimeout(AsyncEvent event) {
                interruptWorker();
                close();
            }
            @Override public void onStartAsync(AsyncEvent event) { }
        });
        worker.start();
    }

    @Override
    public String method() {
        return request.getMethod();
    }

    @Override
    public URI uri() {
        var path = applicationPath(request);
        var query = request.getQueryString();
        return URI.create(path + (query == null ? "" : "?" + query));
    }

    @Override
    public Map<String, List<String>> requestHeaders() {
        var result = new LinkedHashMap<String, List<String>>();
        for (var names = request.getHeaderNames(); names.hasMoreElements();) {
            var name = names.nextElement();
            result.put(name, List.copyOf(Collections.list(request.getHeaders(name))));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public String requestHeader(String name) {
        return request.getHeader(name);
    }

    @Override
    public Optional<AuthenticatedIdentity> identity() {
        return identity;
    }

    @Override
    public String mountPath() {
        var mount = request.getContextPath() + request.getServletPath();
        return mount.equals("/") ? "" : mount;
    }

    @Override
    public ClientConnection connection() {
        var address = java.net.InetAddress.ofLiteral(request.getRemoteAddr());
        var scheme = request.getScheme();
        var host = request.getServerName();
        var formatted = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        var port = request.getServerPort();
        var authority = (scheme.equalsIgnoreCase("http") && port == 80
                || scheme.equalsIgnoreCase("https") && port == 443)
                ? formatted
                : formatted + ":" + port;
        return ClientConnection.direct(address, scheme, authority);
    }

    @Override
    public InputStream requestBody() throws IOException {
        return request.getInputStream();
    }

    @Override
    public void responseHeader(String name, List<String> values) {
        if (values.isEmpty()) {
            response.setHeader(name, null);
            return;
        }
        response.setHeader(name, values.getFirst());
        for (var index = 1; index < values.size(); index++) {
            response.addHeader(name, values.get(index));
        }
    }

    @Override
    public void sendResponseHeaders(int status, long length) throws IOException {
        response.setStatus(status);
        if (length > 0) {
            response.setContentLengthLong(length);
        }
        response.flushBuffer();
    }

    @Override
    public OutputStream responseBody() throws IOException {
        return new CompletingOutputStream(response.getOutputStream());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            response.flushBuffer();
        } catch (IOException | IllegalStateException ignored) {
            // A disconnected or recycled response still needs asynchronous completion.
        } finally {
            if (asynchronous != null) {
                try { asynchronous.complete(); }
                catch (IllegalStateException ignored) {
                    // The container completed this context during an error or shutdown dispatch.
                }
            }
        }
    }

    private final class CompletingOutputStream extends FilterOutputStream {
        private CompletingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                ServletTransportExchange.this.close();
            }
        }
    }

    static String applicationPath(HttpServletRequest request) {
        var path = request.getPathInfo();
        if (path == null || path.isEmpty()) {
            var consumed = request.getContextPath().length() + request.getServletPath().length();
            path = request.getRequestURI().substring(Math.min(consumed, request.getRequestURI().length()));
        }
        return path.isEmpty() ? "/" : path;
    }
}
