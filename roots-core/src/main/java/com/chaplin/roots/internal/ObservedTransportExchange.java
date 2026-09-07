package com.chaplin.roots.internal;

import com.chaplin.roots.AuthenticatedIdentity;
import com.chaplin.roots.ClientConnection;
import com.chaplin.roots.ProxyPolicy;
import com.chaplin.roots.RequestObservation;
import com.chaplin.roots.RequestObserver;
import com.chaplin.roots.TraceContext;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Applies trace propagation and exactly-once completion observation to any transport. */
final class ObservedTransportExchange implements TransportExchange {
    private static final System.Logger LOG = System.getLogger(ObservedTransportExchange.class.getName());

    private final TransportExchange delegate;
    private final TraceContext traceContext;
    private final List<RequestObserver> observers;
    private final ClientConnection connection;
    private final String method;
    private final String transportPath;
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final AtomicLong responseBytes = new AtomicLong();
    private final AtomicBoolean completed = new AtomicBoolean();
    private volatile String logicalPath;
    private volatile int status;

    ObservedTransportExchange(TransportExchange delegate, List<RequestObserver> observers) {
        this(delegate, observers, ProxyPolicy.directOnly());
    }

    ObservedTransportExchange(
            TransportExchange delegate,
            List<RequestObserver> observers,
            ProxyPolicy proxyPolicy
    ) {
        this.delegate = delegate;
        this.observers = List.copyOf(observers);
        connection = Objects.requireNonNull(
                Objects.requireNonNull(proxyPolicy, "proxyPolicy")
                        .resolve(delegate.connection(), delegate.requestHeaders()),
                "Proxy policy result"
        );
        traceContext = TraceContext.fromTraceparent(delegate.requestHeader("traceparent"));
        method = safeMethod(delegate.method());
        transportPath = safePath(delegate.uri().getRawPath());
        logicalPath = transportPath;
    }

    void logicalPath(String path) {
        logicalPath = safePath(path);
    }

    @Override
    public String method() {
        return delegate.method();
    }

    @Override
    public URI uri() {
        return delegate.uri();
    }

    @Override
    public Map<String, List<String>> requestHeaders() {
        return delegate.requestHeaders();
    }

    @Override
    public String requestHeader(String name) {
        return delegate.requestHeader(name);
    }

    @Override
    public Optional<AuthenticatedIdentity> identity() {
        return delegate.identity();
    }

    @Override
    public TraceContext traceContext() {
        return traceContext;
    }

    @Override
    public String mountPath() {
        return delegate.mountPath();
    }

    @Override
    public ClientConnection connection() {
        return connection;
    }

    @Override
    public InputStream requestBody() throws IOException {
        return delegate.requestBody();
    }

    @Override
    public void responseHeader(String name, List<String> values) {
        if (name.equalsIgnoreCase("Location")) {
            delegate.responseHeader(name, values.stream().map(this::externalPath).toList());
            return;
        }
        delegate.responseHeader(name, values);
    }

    @Override
    public void sendResponseHeaders(int status, long length) throws IOException {
        this.status = status;
        delegate.responseHeader("traceparent", traceContext.traceparent());
        try {
            delegate.sendResponseHeaders(status, length);
        } catch (IOException | RuntimeException | Error failure) {
            complete();
            throw failure;
        }
    }

    @Override
    public OutputStream responseBody() throws IOException {
        return new FilterOutputStream(delegate.responseBody()) {
            @Override
            public void write(int value) throws IOException {
                out.write(value);
                responseBytes.incrementAndGet();
            }

            @Override
            public void write(byte[] value, int offset, int length) throws IOException {
                out.write(value, offset, length);
                responseBytes.addAndGet(length);
            }

            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    complete();
                }
            }
        };
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } finally {
            complete();
        }
    }

    private void complete() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        var elapsed = Math.max(0, System.nanoTime() - startedNanos);
        var observation = new RequestObservation(
                traceContext,
                startedAt,
                Duration.ofNanos(elapsed),
                method,
                logicalPath,
                transportPath,
                status,
                responseBytes.get()
        );
        for (var observer : observers) {
            try {
                observer.onComplete(observation);
            } catch (Throwable failure) {
                LOG.log(System.Logger.Level.WARNING, "Roots request observer failed", failure);
            }
        }
    }

    private static String safeMethod(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            return "UNKNOWN";
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static String safePath(String value) {
        if (value == null || !value.startsWith("/") || value.chars().anyMatch(Character::isISOControl)) {
            return "/";
        }
        return value;
    }

    private String externalPath(String value) {
        var mount = mountPath();
        if (!validMountPath(mount) || mount.isEmpty() || !value.startsWith("/") || value.startsWith("//")
                || value.equals(mount) || value.startsWith(mount + "/")) {
            return value;
        }
        return mount + value;
    }

    private static boolean validMountPath(String value) {
        return value != null && (value.isEmpty()
                || value.matches("/[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*")
                && java.util.Arrays.stream(value.substring(1).split("/"))
                .noneMatch(segment -> segment.equals(".") || segment.equals("..")));
    }
}
