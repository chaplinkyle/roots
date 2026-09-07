package com.chaplin.roots.internal;

import com.chaplin.roots.Response;
import com.chaplin.roots.RootsConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class PrerenderStore implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(PrerenderStore.class.getName());
    private static final Duration FAILURE_RETRY = Duration.ofSeconds(30);

    private final RootsConfig config;
    private final Map<String, Entry> entries;
    private final ExecutorService regenerations;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PrerenderStore(RootsConfig config, Map<String, Entry> entries) {
        this.config = config;
        this.entries = new ConcurrentHashMap<>(entries);
        regenerations = entries.values().stream().anyMatch(entry -> entry.plan.revalidateSeconds() > 0)
                ? Executors.newVirtualThreadPerTaskExecutor()
                : null;
    }

    static PrerenderStore create(RootsConfig config, ConventionRouter router) {
        if (config.development()) {
            return new PrerenderStore(config, Map.of());
        }
        var compiled = new LinkedHashMap<String, PrerenderManifest.Entry>();
        for (var entry : PrerenderManifest.load(config)) {
            compiled.put(entry.path(), entry);
        }
        var entries = new LinkedHashMap<String, Entry>();
        for (var plan : PrerenderEngine.plans(config, router)) {
            var built = compiled.remove(plan.path());
            final PrerenderEngine.GeneratedPage page;
            if (built == null) {
                page = PrerenderEngine.render(
                        config,
                        plan.match(),
                        plan.path(),
                        plan.revalidateSeconds()
                );
            } else {
                if (built.revalidateSeconds() != plan.revalidateSeconds()) {
                    throw new IllegalStateException("Prerender interval mismatch for " + plan.path());
                }
                page = new PrerenderEngine.GeneratedPage(
                        built.path(),
                        built.html(),
                        built.revalidateSeconds()
                );
            }
            entries.put(page.path(), new Entry(plan, page.html()));
        }
        if (!compiled.isEmpty()) {
            throw new IllegalStateException("Prerender manifest contains routes that are no longer configured: "
                    + compiled.keySet());
        }
        return new PrerenderStore(config, entries);
    }

    Response response(String method, String path, String ifNoneMatch, String mountPath) {
        var entry = entries.get(path);
        if (entry == null) {
            return null;
        }
        if (!method.equals("GET") && !method.equals("HEAD")) {
            return Response.methodNotAllowed(method).withHeader("Allow", "GET, HEAD");
        }
        var state = "HIT";
        if (entry.expired() && !closed.get()) {
            state = "STALE";
            if (entry.regenerating.compareAndSet(false, true)) {
                regenerations.submit(() -> regenerate(entry));
            }
        }
        var content = entry.content.mounted(mountPath);
        var headers = headers(content, state);
        if (matchesEtag(ifNoneMatch, content.etag())) {
            return new Response(304, headers, new byte[0]);
        }
        return new Response(200, headers, content.body());
    }

    int size() {
        return entries.size();
    }

    private void regenerate(Entry entry) {
        try {
            var rendered = PrerenderEngine.render(
                    config,
                    entry.plan.match(),
                    entry.plan.path(),
                    entry.plan.revalidateSeconds()
            );
            if (!closed.get()) {
                entry.content = Content.of(rendered.html());
                entry.scheduleNext();
            }
        } catch (Throwable failure) {
            entry.retryAfterFailure();
            LOG.log(System.Logger.Level.WARNING, "Could not regenerate " + entry.plan.path(), failure);
        } finally {
            entry.regenerating.set(false);
        }
    }

    private static Map<String, List<String>> headers(Content content, String state) {
        return Map.of(
                "Content-Type", List.of("text/html; charset=utf-8"),
                "Cache-Control", List.of("public, max-age=0, must-revalidate"),
                "ETag", List.of(content.etag()),
                "X-Roots-Prerender", List.of(state)
        );
    }

    private static boolean matchesEtag(String header, String etag) {
        if (header == null) {
            return false;
        }
        for (var value : header.split(",")) {
            var candidate = value.trim();
            if (candidate.equals("*") || candidate.equals(etag)
                    || candidate.equals("W/" + etag) || (candidate.startsWith("W/")
                    && candidate.substring(2).equals(etag))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || regenerations == null) {
            return;
        }
        regenerations.shutdownNow();
        try {
            regenerations.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Entry {
        private final PrerenderEngine.RenderPlan plan;
        private final AtomicBoolean regenerating = new AtomicBoolean();
        private volatile Content content;
        private volatile long revalidateAt;

        private Entry(PrerenderEngine.RenderPlan plan, String html) {
            this.plan = plan;
            content = Content.of(html);
            scheduleNext();
        }

        private boolean expired() {
            return plan.revalidateSeconds() > 0 && System.nanoTime() - revalidateAt >= 0;
        }

        private void scheduleNext() {
            revalidateAt = deadline(Duration.ofSeconds(plan.revalidateSeconds()));
        }

        private void retryAfterFailure() {
            revalidateAt = deadline(FAILURE_RETRY);
        }
    }

    private record Content(byte[] body, String etag) {
        private Content {
            body = body.clone();
        }

        private static Content of(String html) {
            var body = html.getBytes(StandardCharsets.UTF_8);
            return new Content(body, PrerenderStore.etag(body));
        }

        private Content mounted(String mountPath) {
            if (mountPath.isEmpty()) {
                return this;
            }
            return of(MountPaths.rewriteHtml(new String(body, StandardCharsets.UTF_8), mountPath));
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private static long deadline(Duration duration) {
        if (duration.isZero()) {
            return Long.MAX_VALUE;
        }
        try {
            return Math.addExact(System.nanoTime(), duration.toNanos());
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static String etag(byte[] body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(body);
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
