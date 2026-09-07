package com.chaplin.roots.examples.automation;

import com.chaplin.roots.*;
import com.chaplin.roots.jdbc.JdbcIdempotencyStore;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** A durable inbox. A separate worker may consume queued commands; this example does not execute them. */
public final class Inbox {
    private static final Set<String> OPERATIONS = Set.of("refresh-catalog", "reindex");
    private final DataSource source;
    private final JdbcIdempotencyStore receipts;
    private final WebhookVerifier webhook;

    public Inbox(DataSource source, WebhookVerifier webhook) {
        this.source = source;
        this.receipts = new JdbcIdempotencyStore(source);
        this.webhook = webhook;
    }

    static void createSchema(DataSource source) throws SQLException {
        JdbcIdempotencyStore.createSchema(source);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE automation_commands (command_id CHAR(36) PRIMARY KEY, "
                    + "owner_name VARCHAR(256) NOT NULL, operation VARCHAR(32) NOT NULL, "
                    + "state VARCHAR(32) NOT NULL, created_at_epoch_ms BIGINT NOT NULL)");
        }
    }

    public Response submit(Request request) throws Exception {
        var keys = request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("Idempotency-Key"))
                .flatMap(entry -> entry.getValue().stream()).toList();
        if (keys.size() != 1) throw new IllegalArgumentException("One idempotency key is required");
        var owner = request.identity().orElseThrow().name();
        return accept(request, owner, "api:" + owner, keys.getFirst());
    }

    public Response ingest(Request request) throws Exception {
        if (webhook == null) return problem(503, "webhooks-disabled", "Webhook ingestion is not configured.");
        var verified = webhook.verify(request);
        if (verified.isEmpty()) return problem(401, "invalid-signature", "A valid webhook signature is required.");
        return accept(request, "automation-client", "webhook:configured-sender", verified.orElseThrow().id());
    }

    private Response accept(Request request, String owner, String scope, String key) throws Exception {
        var contentType = request.header("Content-Type").orElse("").split(";", 2)[0].strip();
        if (!contentType.equalsIgnoreCase("application/x-www-form-urlencoded")) {
            return problem(415, "unsupported-media-type", "Send application/x-www-form-urlencoded command fields.");
        }
        var id = canonicalId(one(request, "command_id"));
        var operation = one(request, "operation");
        if (!OPERATIONS.contains(operation) || request.form().size() != 2) {
            throw new IllegalArgumentException("Unsupported command fields");
        }
        return receipts.execute(scope, key, IdempotencyStore.fingerprint(request), Duration.ofHours(24), connection -> {
            try (var select = prepare(connection,
                    "SELECT owner_name, operation FROM automation_commands WHERE command_id = ?")) {
                select.setString(1, id);
                try (var rows = select.executeQuery()) {
                    if (rows.next()) {
                        return owner.equals(rows.getString(1)) && operation.equals(rows.getString(2))
                                ? accepted(200, id, request.mountPath())
                                : problem(409, "command-conflict", "Use a new command ID for a different operation.");
                    }
                }
            }
            try (var insert = prepare(connection, "INSERT INTO automation_commands "
                    + "(command_id, owner_name, operation, state, created_at_epoch_ms) VALUES (?, ?, ?, 'queued', ?)")) {
                insert.setString(1, id);
                insert.setString(2, owner);
                insert.setString(3, operation);
                insert.setLong(4, Instant.now().toEpochMilli());
                insert.executeUpdate();
            }
            return accepted(201, id, request.mountPath());
        });
    }

    public Response status(Request request) throws Exception {
        var id = canonicalId(request.parameters().get("id"));
        try (var connection = source.getConnection(); var select = prepare(connection,
                "SELECT operation, state FROM automation_commands WHERE command_id = ? AND owner_name = ?")) {
            select.setString(1, id);
            select.setString(2, request.identity().orElseThrow().name());
            try (var rows = select.executeQuery()) {
                if (!rows.next()) return problem(404, "command-not-found", "No command with that ID exists for this caller.");
                var operation = rows.getString(1);
                var state = rows.getString(2);
                // Only fixed protocol tokens are interpolated into JSON, never arbitrary input.
                if (!OPERATIONS.contains(operation) || !Set.of("queued", "running", "completed", "failed").contains(state)) {
                    throw new SQLException("Unsupported stored command representation");
                }
                return Response.json(200, "{\"id\":\"" + id + "\",\"operation\":\"" + operation
                        + "\",\"state\":\"" + state + "\"}").withHeader("Cache-Control", "no-store");
            }
        }
    }

    private static String one(Request request, String name) {
        var values = request.formValues(name);
        if (values.size() != 1) throw new IllegalArgumentException("One " + name + " is required");
        return values.getFirst();
    }

    private static String canonicalId(String value) {
        var id = UUID.fromString(value).toString();
        if (!id.equals(value)) throw new IllegalArgumentException("Use a canonical UUID");
        return id;
    }

    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try { statement.setQueryTimeout(5); return statement; }
        catch (SQLException failure) { statement.close(); throw failure; }
    }

    private static Response accepted(int status, String id, String mountPath) {
        return Response.json(status, "{\"id\":\"" + id + "\",\"accepted\":true}")
                .withHeader("Location", mountPath + "/api/commands/" + id).withHeader("Cache-Control", "no-store");
    }

    static Response problem(int status, String code, String detail) {
        return ProblemDetail.of(URI.create("urn:roots:automation:" + code), status, "Automation request", detail).response();
    }
}
