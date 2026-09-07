package com.chaplin.roots.examples.automation;

import com.chaplin.roots.*;
import com.chaplin.roots.annotation.RootsApplication;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

@RootsApplication
public final class Application {
    private Application() { }

    public static void main(String[] args) throws Exception {
        var env = System.getenv();
        try (var pool = pool(required(env, "AUTOMATION_JDBC_URL"),
                env.getOrDefault("AUTOMATION_JDBC_USER", ""), env.getOrDefault("AUTOMATION_JDBC_PASSWORD", ""))) {
            if (args.length == 1 && args[0].equals("--migrate")) {
                Inbox.createSchema(pool);
                System.out.println("Created the automation inbox and receipt tables.");
                return;
            }
            if (args.length == 1 && args[0].equals("--prune-receipts")) {
                int removed = new com.chaplin.roots.jdbc.JdbcIdempotencyStore(pool)
                        .deleteExpired(java.time.Instant.now(), 1_000);
                System.out.println("Removed " + removed + " expired receipts.");
                return;
            }
            var webhookSecret = env.get("AUTOMATION_WEBHOOK_SECRET");
            var webhook = webhookSecret == null ? null
                    : new WebhookVerifier(List.of(webhookSecret), Duration.ofMinutes(5));
            Roots.run(config(pool, required(env, "AUTOMATION_TOKEN"), webhook, args));
        }
    }

    static HikariDataSource pool(String url, String user, String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(2_000);
        config.setValidationTimeout(1_000);
        config.setPoolName("automation");
        return new HikariDataSource(config);
    }

    static RootsConfig config(DataSource source, String token, WebhookVerifier webhook, String... args) {
        if (!token.matches("[A-Za-z0-9_-]{32,256}")) {
            throw new IllegalArgumentException("AUTOMATION_TOKEN must contain 32 to 256 URL-safe random characters");
        }
        var expected = token.getBytes(StandardCharsets.US_ASCII);
        var inbox = new Inbox(source, webhook);
        return RootsConfig.forApplication(Application.class).development(false)
                .maxLiveViews(1).maxSessions(1).maxConcurrentRequests(64).maxRequestBytes(16_384)
                .authenticationProvider(AuthenticationProvider.bearer(candidate ->
                        MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))
                                ? Optional.of(AuthenticatedIdentity.of("automation-client", Set.of("automation")))
                                : Optional.empty()))
                .authorize("automation", AuthorizationPolicy.authority("automation",
                        Inbox.problem(401, "unauthorized", "A valid automation credential is required.")
                                .withHeader("WWW-Authenticate", "Bearer")))
                .instanceFactory(type -> type.getDeclaredConstructor(Inbox.class).newInstance(inbox))
                .mapException(IllegalArgumentException.class,
                        (request, failure) -> Inbox.problem(400, "invalid-request", "Check the command ID, operation, and idempotency key."))
                .mapException(SQLException.class, (request, failure) ->
                        "23505".equals(failure.getSQLState())
                                ? Inbox.problem(409, "command-conflict", "The command ID already exists; check its status.")
                                : Inbox.problem(503, "storage-unavailable", "Retry with the same command ID and idempotency key.")
                                        .withHeader("Retry-After", "1"))
                .environment().arguments(args).build();
    }

    private static String required(Map<String, String> env, String name) {
        var value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name);
        return value;
    }
}
