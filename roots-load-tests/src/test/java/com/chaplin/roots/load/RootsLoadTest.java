package com.chaplin.roots.load;

import com.chaplin.roots.Roots;
import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.RunningApplication;
import com.chaplin.roots.RuntimeSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RootsLoadTest {
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");
    private static final Pattern ACTION = Pattern.compile("data-roots-on-click=\"([^\"]+)\"");
    private static final Pattern REVISION = Pattern.compile("\"revision\":(\\d+)");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void sustainsParallelUsersWithoutLostStateRejectionsOrViewLeaks() throws Exception {
        var users = 16;
        var actionsPerUser = 30;
        try (var application = start(users)) {
            var latencies = new LatencyHistogram();
            var started = System.nanoTime();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks = new ArrayList<Callable<Void>>();
                for (var user = 0; user < users; user++) {
                    tasks.add(() -> {
                        runUser(application.uri(), actionsPerUser, latencies);
                        return null;
                    });
                }
                for (Future<Void> result : executor.invokeAll(tasks)) {
                    result.get(30, TimeUnit.SECONDS);
                }
            }
            var elapsed = Duration.ofNanos(System.nanoTime() - started);
            var snapshot = completedSnapshot(application, users * (actionsPerUser + 2L));
            var p99 = latencies.percentile(0.99);

            assertEquals(0, snapshot.liveViews());
            assertEquals(0, snapshot.activeRequests());
            assertEquals(0, snapshot.rejectedRequests());
            assertEquals(users * (actionsPerUser + 2L), snapshot.handledRequests());
            assertTrue(snapshot.peakActiveRequests() >= 2, snapshot.toString());
            assertTrue(elapsed.compareTo(Duration.ofSeconds(30)) < 0, "Load gate took " + elapsed);
            assertTrue(p99.compareTo(Duration.ofSeconds(5)) < 0, "p99 action latency was " + p99);
            System.out.printf("Roots load gate: actions=%d elapsed=%s p99=%s peakRequests=%d%n",
                    users * actionsPerUser, elapsed, p99, snapshot.peakActiveRequests());
        }
    }

    @Test
    void serializesAContendedLiveViewWithoutDuplicateRevisions() throws Exception {
        var actions = 128;
        try (var application = start(1, actions + 16)) {
            var view = open(application.uri());
            var requests = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
            for (var index = 0; index < actions; index++) {
                requests.add(CLIENT.sendAsync(actionRequest(application.uri(), view),
                        HttpResponse.BodyHandlers.ofString()));
            }
            java.util.concurrent.CompletableFuture.allOf(requests.toArray(java.util.concurrent.CompletableFuture[]::new))
                    .get(30, TimeUnit.SECONDS);

            var revisions = new TreeSet<Integer>();
            var sawFinalState = false;
            for (var request : requests) {
                var response = request.join();
                assertEquals(200, response.statusCode(), response.body());
                revisions.add(revision(response.body()));
                sawFinalState |= response.body().contains("Count " + actions);
            }
            assertEquals(actions, revisions.size());
            assertEquals(2, revisions.getFirst());
            assertEquals(actions + 1, revisions.getLast());
            assertTrue(sawFinalState);
            dispose(application.uri(), view);

            assertEquals(0, application.runtimeSnapshot().liveViews());
            assertEquals(0, application.runtimeSnapshot().rejectedRequests());
        }
    }

    @Test
    @Tag("soak")
    void sustainedConfigurableTrafficPreservesEveryView() throws Exception {
        var users = Integer.getInteger("roots.soak.users", 32);
        var seconds = Integer.getInteger("roots.soak.seconds", 60);
        assertTrue(users > 0 && seconds > 0, "Soak users and duration must be positive");

        try (var application = start(users)) {
            var deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
            var actions = new ConcurrentLinkedQueue<Integer>();
            var latencies = new LatencyHistogram();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks = new ArrayList<Callable<Void>>();
                for (var user = 0; user < users; user++) {
                    tasks.add(() -> {
                        actions.add(runUntil(application.uri(), deadline, latencies));
                        return null;
                    });
                }
                for (var result : executor.invokeAll(tasks)) {
                    result.get(seconds + 30L, TimeUnit.SECONDS);
                }
            }

            var completed = actions.stream().mapToInt(Integer::intValue).sum();
            var snapshot = completedSnapshot(application, completed + users * 2L);
            var p99 = latencies.percentile(0.99);
            assertTrue(completed >= users * 10, "Only " + completed + " actions completed");
            assertEquals(0, snapshot.liveViews());
            assertEquals(0, snapshot.activeRequests());
            assertEquals(0, snapshot.rejectedRequests());
            assertTrue(snapshot.peakActiveRequests() >= 2, snapshot.toString());
            assertTrue(p99.compareTo(Duration.ofSeconds(5)) < 0, "p99 action latency was " + p99);
            System.out.printf("Roots soak: users=%d seconds=%d actions=%d p99=%s peakRequests=%d%n",
                    users, seconds, completed, p99, snapshot.peakActiveRequests());
        }
    }

    private static RuntimeSnapshot completedSnapshot(RunningApplication application, long expectedRequests)
            throws InterruptedException {
        // The client can receive the last response before the transport's finally
        // block updates completion counters. Still require full bounded cleanup.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        RuntimeSnapshot snapshot;
        do {
            snapshot = application.runtimeSnapshot();
            if (snapshot.activeRequests() == 0 && snapshot.liveViews() == 0
                    && snapshot.handledRequests() == expectedRequests) return snapshot;
            Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Requests did not finish cleanup: " + snapshot);
    }

    private static RunningApplication start(int users) {
        var liveViewLimit = Math.max(users * 2, 8);
        return start(users, Math.max(liveViewLimit + 1, users * 4));
    }

    private static RunningApplication start(int users, int maxConcurrentRequests) {
        var liveViewLimit = Math.max(users * 2, 8);
        return Roots.start(RootsConfig.forApplication(Application.class)
                .port(0)
                .development(false)
                .maxLiveViews(liveViewLimit)
                .maxSessions(liveViewLimit)
                .maxConcurrentRequests(maxConcurrentRequests)
                .build());
    }

    private static void runUser(URI base, int actions, LatencyHistogram latencies) throws Exception {
        var view = open(base);
        for (var action = 1; action <= actions; action++) {
            var started = System.nanoTime();
            var response = CLIENT.send(actionRequest(base, view), HttpResponse.BodyHandlers.ofString());
            latencies.add(System.nanoTime() - started);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(action + 1, revision(response.body()));
            assertTrue(response.body().contains("Count " + action), response.body());
        }
        dispose(base, view);
    }

    private static int runUntil(URI base, long deadline, LatencyHistogram latencies) throws Exception {
        var view = open(base);
        var actions = 0;
        while (System.nanoTime() < deadline) {
            var started = System.nanoTime();
            var response = CLIENT.send(actionRequest(base, view), HttpResponse.BodyHandlers.ofString());
            latencies.add(System.nanoTime() - started);
            assertEquals(200, response.statusCode(), response.body());
            actions++;
            assertEquals(actions + 1, revision(response.body()));
        }
        dispose(base, view);
        return actions;
    }

    private static BrowserView open(URI base) throws Exception {
        var response = CLIENT.send(HttpRequest.newBuilder(base).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return new BrowserView(
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                attribute(VIEW, response.body()),
                attribute(CSRF, response.body()),
                attribute(ACTION, response.body())
        );
    }

    private static HttpRequest actionRequest(URI base, BrowserView view) {
        var form = "_view=" + encode(view.id())
                + "&_csrf=" + encode(view.csrf())
                + "&_action=" + encode(view.action())
                + "&_event=click"
                + "&_protocol=" + encode(Roots.PROTOCOL_VERSION);
        return HttpRequest.newBuilder(base.resolve("_roots/action"))
                .header("Cookie", view.cookie())
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
    }

    private static void dispose(URI base, BrowserView view) throws Exception {
        var form = "_view=" + encode(view.id())
                + "&_csrf=" + encode(view.csrf())
                + "&_protocol=" + encode(Roots.PROTOCOL_VERSION);
        var response = CLIENT.send(HttpRequest.newBuilder(base.resolve("_roots/dispose"))
                .header("Cookie", view.cookie())
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode(), response.body());
    }

    private static String attribute(Pattern pattern, String body) {
        var matcher = pattern.matcher(body);
        assertTrue(matcher.find(), body);
        return matcher.group(1);
    }

    private static int revision(String body) {
        return Integer.parseInt(attribute(REVISION, body));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }


    private record BrowserView(String cookie, String id, String csrf, String action) {
    }
}
