package com.chaplin.roots.load;

import com.chaplin.roots.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Closed-loop measurement with a dedicated disposable database, never production data. */
@Tag("soak")
final class BusinessLoadTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final LatencyHistogram actions = new LatencyHistogram(), pushes = new LatencyHistogram();
    private final LongAdder actionBytes = new LongAdder(), streamBytes = new LongAdder();
    private final LongAdder skippedPushes = new LongAdder();
    private final AtomicLong peakHeap = new AtomicLong(), peakPool = new AtomicLong(), peakWaiters = new AtomicLong();
    private final AtomicBoolean monitoring = new AtomicBoolean(true);

    @Test void measuresLargeDatabaseViewsWithConcurrentActionsAndSse() throws Exception {
        int users = Integer.getInteger("roots.business.users", 16);
        int rows = Integer.getInteger("roots.business.rows", 1000);
        int seconds = Integer.getInteger("roots.business.seconds", 30);
        int thinkMs = Integer.getInteger("roots.business.thinkMs", 10);
        int pushMs = Integer.getInteger("roots.business.pushMs", 1000);
        assertTrue(users > 0 && rows > 0 && seconds > 0 && thinkMs >= 0 && pushMs > 0);
        var hikari = new HikariConfig();
        hikari.setJdbcUrl(System.getenv().getOrDefault("ROOTS_LOAD_JDBC_URL", "jdbc:h2:mem:roots-business;DB_CLOSE_DELAY=-1"));
        hikari.setUsername(System.getenv().getOrDefault("ROOTS_LOAD_DB_USER", "sa"));
        hikari.setPassword(System.getenv().getOrDefault("ROOTS_LOAD_DB_PASSWORD", ""));
        hikari.setMaximumPoolSize(Integer.getInteger("roots.business.pool", 8));
        hikari.setConnectionTimeout(10_000);
        try (var pool = new HikariDataSource(hikari);
             var client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
             var application = Roots.start(RootsConfig.forApplication(Application.class).port(0).development(false)
                     .maxLiveViews(users + 2).maxSessions(users + 2).maxConcurrentRequests(users * 4 + 8).build())) {
            BusinessStore.pool = pool;
            BusinessStore.rows = rows;
            BusinessStore.checkout = new LatencyHistogram(); BusinessStore.transaction = new LatencyHistogram();
            String run = UUID.randomUUID().toString();
            seed(pool, run, users, rows);
            System.gc();
            long baselineHeap = heap(), gcCount = gc(false), gcMillis = gc(true);
            var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (allocation.isThreadAllocatedMemorySupported()) allocation.setThreadAllocatedMemoryEnabled(true);
            long allocated = allocation.getTotalThreadAllocatedBytes();
            long cpu = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
            long started = System.nanoTime();
            var opened = new CountDownLatch(users);
            var go = new CountDownLatch(1);
            var deadline = new AtomicLong();
            var completed = new AtomicLongArray(users);
            var streams = new ConcurrentLinkedQueue<InputStream>();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var monitor = executor.submit(() -> {
                    while (monitoring.get()) {
                        peakHeap.accumulateAndGet(heap(), Math::max);
                        var bean = pool.getHikariPoolMXBean();
                        peakPool.accumulateAndGet(bean.getActiveConnections(), Math::max);
                        peakWaiters.accumulateAndGet(bean.getThreadsAwaitingConnection(), Math::max);
                        Thread.sleep(50);
                    }
                    return null;
                });
                var tasks = new ArrayList<Future<?>>();
                for (int index = 0; index < users; index++) {
                    final int user = index;
                    tasks.add(executor.submit(() -> {
                        var page = send(client, HttpRequest.newBuilder(application.uri().resolve("business?user=" + run + "-" + user)).timeout(TIMEOUT).GET().build());
                        var view = new View(page.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                                match("data-roots-view=\"([^\"]+)\"", page.body()), match("data-roots-csrf=\"([^\"]+)\"", page.body()),
                                match("data-roots-on-submit=\"([^\"]+)\"", page.body()));
                        var response = client.send(HttpRequest.newBuilder(application.uri().resolve("_roots/stream?view=" + enc(view.id)
                                + "&csrf=" + enc(view.csrf) + "&protocol=" + enc(Roots.PROTOCOL_VERSION)))
                                .header("Cookie", view.cookie).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                        assertEquals(200, response.statusCode());
                        streams.add(response.body());
                        var closing = new AtomicBoolean();
                        var reader = executor.submit(() -> {
                            long previous = 0;
                            try (var lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = lines.readLine()) != null) {
                                    streamBytes.add(line.getBytes(StandardCharsets.UTF_8).length + 1);
                                    if (!line.startsWith("data: ") || !line.contains("data-pulse")) continue;
                                    long sequence = Long.parseLong(match("data-pulse=.{1,3}?(\\d+)", line));
                                    if (sequence > previous) {
                                        skippedPushes.add(sequence - previous - 1);
                                        previous = sequence;
                                        long sent = Long.parseLong(match("data-sent=.{1,3}?(\\d+)", line));
                                        pushes.add(System.nanoTime() - sent);
                                    }
                                }
                            } catch (IOException failure) {
                                if (monitoring.get() && !closing.get()) throw new UncheckedIOException(failure);
                            }
                            assertTrue(previous > 0, "This view did not receive a background update");
                            return null;
                        });
                        opened.countDown();
                        go.await();
                        long lastRevision = 0, count = 0;
                        try {
                            while (System.nanoTime() < deadline.get()) {
                                var body = view.form() + "&_action=" + enc(view.action) + "&_event=submit&account=0&status=Saved-" + (count + 1);
                                long began = System.nanoTime();
                                var result = send(client, request(application, view, "action", body));
                                actions.add(System.nanoTime() - began);
                                actionBytes.add(result.body().getBytes(StandardCharsets.UTF_8).length);
                                long revision = Long.parseLong(match("\"revision\":(\\d+)", result.body()));
                                assertTrue(revision > lastRevision, "Non-increasing revision");
                                lastRevision = revision;
                                assertTrue(result.body().contains("Saved-" + (count + 1)), "Lost committed state");
                                count++;
                                if (thinkMs > 0) Thread.sleep(thinkMs);
                            }
                            completed.set(user, count);
                        } finally {
                            closing.set(true);
                            var disposed = client.send(request(application, view, "dispose", view.form()), HttpResponse.BodyHandlers.ofString());
                            assertEquals(204, disposed.statusCode());
                            response.body().close();
                            reader.get(5, TimeUnit.SECONDS);
                        }
                        return null;
                    }));
                }
                try {
                    assertTrue(opened.await(60, TimeUnit.SECONDS), "Views did not open");
                    System.gc();
                    long liveHeap = heap();
                    deadline.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds));
                    long trafficStart = System.nanoTime();
                    go.countDown();
                    var producer = executor.submit(() -> {
                        while (System.nanoTime() < deadline.get()) {
                            var batch = new ArrayList<Callable<Void>>();
                            for (var update : BusinessStore.UPDATES.values()) batch.add(() -> { update.run(); return null; });
                            for (var result : executor.invokeAll(batch, 30, TimeUnit.SECONDS)) result.get();
                            Thread.sleep(pushMs);
                        }
                        return null;
                    });
                    for (var task : tasks) task.get(seconds + 60L, TimeUnit.SECONDS);
                    producer.get(30, TimeUnit.SECONDS);
                    double trafficSeconds = (System.nanoTime() - trafficStart) / 1e9;
                    monitoring.set(false); monitor.get(5, TimeUnit.SECONDS);
                    assertTrue(pushes.count() >= users, "No push delivery for every view");
                    assertEquals(0, application.runtimeSnapshot().liveViews());
                    assertEquals(0, application.runtimeSnapshot().rejectedRequests());
                    assertTrue(BusinessStore.UPDATES.isEmpty());
                    for (int user = 0; user < users; user++) {
                        var records = BusinessStore.read(run + "-" + user);
                        assertEquals(rows, records.size());
                        assertEquals(completed.get(user), records.getFirst().version());
                    }
                    long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (application.runtimeSnapshot().activeRequests() > 0 && System.nanoTime() < cleanupDeadline) Thread.sleep(10);
                    assertEquals(0, application.runtimeSnapshot().activeRequests());
                    tasks.clear(); streams.clear();
                    System.gc();
                    System.out.printf(Locale.ROOT, "Roots business: java=%s db=%s users=%d rows=%d pool=%d thinkMs=%d pushMs=%d trafficSeconds=%.3f actionsPerSecond=%.1f%n",
                            System.getProperty("java.version"), hikari.getJdbcUrl().startsWith("jdbc:postgresql:") ? "PostgreSQL" : "H2",
                            users, rows, hikari.getMaximumPoolSize(), thinkMs, pushMs, trafficSeconds, actions.count() / trafficSeconds);
                    System.out.println("Business HTTP: " + actions.summary());
                    System.out.println("Business SSE: " + pushes.summary() + " coalesced=" + skippedPushes.sum());
                    System.out.println("Business pool checkout: " + BusinessStore.checkout.summary());
                    System.out.println("Business SQL transaction: " + BusinessStore.transaction.summary());
                    System.out.printf(Locale.ROOT, "Business resources: baselineHeap=%d liveHeap=%d peakHeap=%d cleanupHeap=%d maxHeap=%d allocatedBytes=%d gcCount=%d gcMillis=%d cpuSeconds=%.3f elapsedSeconds=%.3f poolPeak=%d poolWaitersPeak=%d actionBytes=%d streamBytes=%d%n",
                            baselineHeap, liveHeap, peakHeap.get(), heap(), Runtime.getRuntime().maxMemory(),
                            allocated < 0 ? -1 : allocation.getTotalThreadAllocatedBytes() - allocated, gc(false) - gcCount, gc(true) - gcMillis,
                            (((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime() - cpu) / 1e9,
                            (System.nanoTime() - started) / 1e9, peakPool.get(), peakWaiters.get(), actionBytes.sum(), streamBytes.sum());
                } finally {
                    monitoring.set(false); go.countDown(); deadline.set(0);
                    for (var stream : streams) try { stream.close(); } catch (IOException ignored) {}
                    for (var task : tasks) task.cancel(true);
                }
            } finally {
                try (var connection = pool.getConnection(); var statement = connection.createStatement()) { statement.execute("DROP TABLE roots_load_account"); }
            }
        } finally { BusinessStore.pool = null; BusinessStore.UPDATES.clear(); }
    }

    private static void seed(HikariDataSource pool, String run, int users, int rows) throws Exception {
        try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
            // Fail if the table exists: the operator must supply a dedicated empty database.
            statement.execute("CREATE TABLE roots_load_account (user_id VARCHAR(80),account_id INT,version INT NOT NULL,status VARCHAR(40) NOT NULL,PRIMARY KEY(user_id,account_id))");
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT INTO roots_load_account VALUES(?,?,0,'Active')")) {
                for (int user = 0; user < users; user++) {
                    for (int row = 0; row < rows; row++) {
                        insert.setString(1, run + "-" + user); insert.setInt(2, row); insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            }
        }
    }
    private static long heap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
    private static long gc(boolean time) { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, time ? bean.getCollectionTime() : bean.getCollectionCount())).sum(); }
    private static String match(String regex, String body) { var matcher = Pattern.compile(regex).matcher(body); if (!matcher.find()) throw new AssertionError("Missing " + regex + " in response"); return matcher.group(1); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception { var response = client.send(request, HttpResponse.BodyHandlers.ofString()); assertEquals(200, response.statusCode(), () -> response.body().substring(0, Math.min(300, response.body().length()))); return response; }
    private static HttpRequest request(RunningApplication app, View view, String endpoint, String body) {
        return HttpRequest.newBuilder(app.uri().resolve("_roots/" + endpoint)).timeout(TIMEOUT).header("Cookie", view.cookie)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private record View(String cookie, String id, String csrf, String action) {
        String form() { return "_view=" + enc(id) + "&_csrf=" + enc(csrf) + "&_protocol=" + enc(Roots.PROTOCOL_VERSION); }
    }
}
