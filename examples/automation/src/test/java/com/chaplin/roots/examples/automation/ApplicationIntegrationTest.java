package com.chaplin.roots.examples.automation;

import com.chaplin.roots.Roots;
import com.chaplin.roots.RunningApplication;
import com.chaplin.roots.WebhookVerifier;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ApplicationIntegrationTest {
    private static final String TOKEN = "test_automation_credential_32_characters";
    private static final byte[] WEBHOOK_KEY = "test_webhook_credential_32_bytes_".getBytes(StandardCharsets.US_ASCII);
    private static final String WEBHOOK_SECRET = "whsec_" + Base64.getEncoder().encodeToString(WEBHOOK_KEY);
    @TempDir Path directory;

    @Test
    void concurrentAuthenticatedCallersCreateOneCommandWithoutBrowserSessions() throws Exception {
        try (var pool = pool(); var client = HttpClient.newHttpClient()) {
            Inbox.createSchema(pool);
            try (var app = start(pool)) {
                var id = UUID.randomUUID().toString();
                var body = body(id, "reindex");
                assertEquals(401, send(client, post(app.uri(), body, "key").build()).statusCode());
                var responses = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int index = 0; index < 64; index++) {
                        responses.add(executor.submit(() -> send(client, authorized(app.uri(), body, "same-key"))));
                    }
                    int originals = 0;
                    for (var future : responses) {
                        var response = future.get();
                        assertEquals(201, response.statusCode(), response.body());
                        assertTrue(response.body().contains(id));
                        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
                        assertTrue(response.headers().firstValue("traceparent").isPresent());
                        if (response.headers().firstValue("Idempotency-Replayed").orElseThrow().equals("false")) originals++;
                    }
                    assertEquals(1, originals);
                }
                assertEquals(1, count(pool));
                assertEquals(0, app.runtimeSnapshot().sessions());
                assertEquals(0, app.runtimeSnapshot().liveViews());
                assertEquals(422, send(client, authorized(app.uri(), body(id, "refresh-catalog"), "same-key")).statusCode());
            }
        }
    }

    @Test
    void lostResponseAndDatabaseReopenReplayWithoutRepeatingTheBusinessInsert() throws Exception {
        var id = UUID.randomUUID().toString();
        var body = body(id, "reindex");
        String original;
        try (var pool = pool(); var client = HttpClient.newHttpClient()) {
            Inbox.createSchema(pool);
            try (var app = start(pool)) {
                // Treat the first response as lost to the caller; restart all app/pool state.
                original = send(client, authorized(app.uri(), body, "resume-key")).body();
            }
        }
        try (var pool = pool(); var client = HttpClient.newHttpClient(); var app = start(pool)) {
            var replay = send(client, authorized(app.uri(), body, "resume-key"));
            assertEquals(201, replay.statusCode());
            assertEquals(original, replay.body());
            assertEquals("true", replay.headers().firstValue("Idempotency-Replayed").orElseThrow());
            assertEquals(200, send(client, authorized(app.uri(), body, "new-receipt-key")).statusCode());
            assertEquals(409, send(client, authorized(app.uri(), body(id, "refresh-catalog"), "different-operation")).statusCode());
            assertEquals(1, count(pool));
            try (var connection = pool.getConnection(); var expire = connection.createStatement()) {
                expire.executeUpdate("UPDATE roots_operation_receipts SET expires_at_epoch_ms = 0");
            }
            assertTrue(new com.chaplin.roots.jdbc.JdbcIdempotencyStore(pool).deleteExpired(Instant.now(), 100) > 0);
            assertEquals(200, send(client, authorized(app.uri(), body, "resume-key")).statusCode());
            assertEquals(1, count(pool), "The permanent command ID protects writes after receipt expiry");
            var status = send(client, HttpRequest.newBuilder(app.uri().resolve("/api/commands/" + id))
                    .header("Authorization", "Bearer " + TOKEN).build());
            assertEquals(200, status.statusCode());
            assertTrue(status.body().contains("\"state\":\"queued\""));
            assertTrue(status.body().contains("\"operation\":\"reindex\""));
            assertEquals(404, send(client, HttpRequest.newBuilder(app.uri().resolve("/api/commands/" + UUID.randomUUID()))
                    .header("Authorization", "Bearer " + TOKEN).build()).statusCode());
        }
    }

    @Test
    void verifiesRawWebhookBytesAndPersistsDuplicateDeliveryReceipts() throws Exception {
        try (var pool = pool(); var client = HttpClient.newHttpClient()) {
            Inbox.createSchema(pool);
            try (var app = start(pool)) {
                var body = body(UUID.randomUUID().toString(), "refresh-catalog");
                var now = Instant.now().getEpochSecond();
                var first = webhook(app.uri(), body, "delivery-1", now);
                assertEquals(201, send(client, first).statusCode());
                var repeat = send(client, webhook(app.uri(), body, "delivery-1", now + 1));
                assertEquals(201, repeat.statusCode());
                assertEquals("true", repeat.headers().firstValue("Idempotency-Replayed").orElseThrow());
                assertEquals(1, count(pool));
                var unsigned = HttpRequest.newBuilder(app.uri().resolve("/api/webhooks"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                assertEquals(401, send(client, unsigned).statusCode());
                assertEquals(401, send(client, webhook(app.uri(), body, "delivery-stale", now - 3600)).statusCode());
                assertEquals(1, count(pool));
                assertEquals(0, app.runtimeSnapshot().sessions());
            }
        }
    }

    @Test
    void rejectsAmbiguousOrUnsupportedCommandsBeforeWriting() throws Exception {
        try (var pool = pool(); var client = HttpClient.newHttpClient()) {
            Inbox.createSchema(pool);
            try (var app = start(pool)) {
                var body = body(UUID.randomUUID().toString(), "reindex");
                var repeatedKey = post(app.uri(), body, "one").header("Idempotency-Key", "two")
                        .header("Authorization", "Bearer " + TOKEN).build();
                assertEquals(400, send(client, repeatedKey).statusCode());
                for (var invalid : List.of(body + "&operation=reindex", body.replace("reindex", "run-shell"),
                        "command_id=bad&operation=reindex")) {
                    assertEquals(400, send(client, authorized(app.uri(), invalid, "invalid")).statusCode());
                }
                var json = HttpRequest.newBuilder(app.uri().resolve("/api/commands"))
                        .header("Content-Type", "application/json").header("Idempotency-Key", "json")
                        .header("Authorization", "Bearer " + TOKEN).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                assertEquals(415, send(client, json).statusCode());
                assertEquals(0, count(pool));
            }
        }
    }

    private com.zaxxer.hikari.HikariDataSource pool() {
        return Application.pool("jdbc:h2:file:" + directory.resolve("inbox").toAbsolutePath().toString().replace('\\', '/'), "sa", "");
    }
    private static RunningApplication start(DataSource source) {
        return Roots.start(Application.config(source, TOKEN,
                new WebhookVerifier(List.of(WEBHOOK_SECRET), Duration.ofMinutes(5)), "--port=0"));
    }
    private static String body(String id, String operation) { return "command_id=" + id + "&operation=" + operation; }
    private static HttpRequest.Builder post(URI base, String body, String key) {
        return HttpRequest.newBuilder(base.resolve("/api/commands"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(15));
    }
    private static HttpRequest authorized(URI base, String body, String key) {
        return post(base, body, key).header("Authorization", "Bearer " + TOKEN).build();
    }
    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private static int count(DataSource source) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM automation_commands")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
    private static HttpRequest webhook(URI base, String body, String id, long timestamp) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
        var signature = Base64.getEncoder().encodeToString(mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        return HttpRequest.newBuilder(base.resolve("/api/webhooks"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("webhook-id", id).header("webhook-timestamp", Long.toString(timestamp))
                .header("webhook-signature", "v1," + signature)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
