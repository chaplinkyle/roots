package com.chaplin.roots.jdbc;

import com.chaplin.roots.LiveViewOwner;
import com.chaplin.roots.LiveViewOwnershipException;
import com.chaplin.roots.Roots;
import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.SessionRepository;
import com.chaplin.roots.jdbc.fixture.Application;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcLiveViewOwnershipTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");

    @Test
    void createsSchemaAndSharesTheCompleteLeaseLifecycle() {
        var dataSource = dataSource();
        JdbcLiveViewOwnership.createSchema(dataSource);
        var first = new JdbcLiveViewOwnership(dataSource, "node-a");
        var second = new JdbcLiveViewOwnership(dataSource, "node-b");
        var now = Instant.parse("2026-08-16T12:00:00Z");

        assertTrue(first.claim("view-a", "session-a", now, now.plusSeconds(10)));
        assertFalse(second.claim("view-a", "session-a", now, now.plusSeconds(10)));
        assertTrue(first.renew("view-a", "session-a", now.plusSeconds(1), now.plusSeconds(20)));
        assertFalse(second.renew("view-a", "session-a", now.plusSeconds(1), now.plusSeconds(20)));
        assertEquals(new LiveViewOwner("node-a", "session-a", now.plusSeconds(20)),
                second.find("view-a", now.plusSeconds(2)).orElseThrow());

        second.release("view-a", "session-a");
        assertTrue(first.find("view-a", now.plusSeconds(2)).isPresent());
        first.release("view-a", "wrong-session");
        assertTrue(first.find("view-a", now.plusSeconds(2)).isPresent());
        first.release("view-a", "session-a");
        assertTrue(first.find("view-a", now.plusSeconds(2)).isEmpty());

        assertTrue(first.claim("expired", "session-a", now, now.plusSeconds(1)));
        first.deleteExpired(now.plusSeconds(1));
        assertTrue(second.find("expired", now).isEmpty());
        first.close();
        second.close();
    }

    @Test
    void exactlyOneNodeWinsConcurrentClaims() throws Exception {
        var dataSource = dataSource();
        JdbcLiveViewOwnership.createSchema(dataSource);
        var first = new JdbcLiveViewOwnership(dataSource, "node-a");
        var second = new JdbcLiveViewOwnership(dataSource, "node-b");
        var now = Instant.parse("2026-08-16T12:00:00Z");
        var attempts = new ArrayList<Attempt>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Callable<Attempt>>();
            for (var index = 0; index < 200; index++) {
                var ownership = index % 2 == 0 ? first : second;
                tasks.add(() -> new Attempt(ownership.localNodeId(),
                        ownership.claim("contended", "session-a", now, now.plusSeconds(30))));
            }
            for (var future : executor.invokeAll(tasks)) {
                attempts.add(future.get());
            }
        }

        var winner = first.find("contended", now).orElseThrow().nodeId();
        assertTrue(attempts.stream().anyMatch(attempt -> attempt.nodeId().equals("node-a")));
        assertTrue(attempts.stream().anyMatch(attempt -> attempt.nodeId().equals("node-b")));
        attempts.forEach(attempt -> assertEquals(attempt.nodeId().equals(winner), attempt.claimed(), attempt.toString()));
    }

    @Test
    void staleSessionCannotChangeAReplacementLease() {
        var dataSource = dataSource();
        JdbcLiveViewOwnership.createSchema(dataSource, "custom_ownership");
        var ownership = new JdbcLiveViewOwnership(dataSource, "node-a", "custom_ownership");
        var now = Instant.parse("2026-08-16T12:00:00Z");

        assertTrue(ownership.claim("view", "session-old", now, now.plusSeconds(1)));
        assertTrue(ownership.claim("view", "session-new", now.plusSeconds(2), now.plusSeconds(12)));
        assertFalse(ownership.renew("view", "session-old", now.plusSeconds(3), now.plusSeconds(13)));
        ownership.release("view", "session-old");

        assertEquals("session-new", ownership.find("view", now.plusSeconds(3)).orElseThrow().sessionId());
    }

    @Test
    void twoRealRootsNodesReturnTheJdbcOwnerHint() throws Exception {
        var dataSource = dataSource();
        JdbcLiveViewOwnership.createSchema(dataSource);
        JdbcSessionRepository.createSchema(dataSource);
        var sessions = new JdbcSessionRepository(dataSource);
        try (var nodeA = Roots.start(RootsConfig.forApplication(Application.class)
                .port(0).development(false).sessionRepository(sessions)
                .liveViewOwnership(new JdbcLiveViewOwnership(dataSource, "jdbc-node-a")).build());
             var nodeB = Roots.start(RootsConfig.forApplication(Application.class)
                     .port(0).development(false).sessionRepository(sessions)
                     .liveViewOwnership(new JdbcLiveViewOwnership(dataSource, "jdbc-node-b")).build())) {
            var page = CLIENT.send(HttpRequest.newBuilder(nodeA.uri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode(), page.body());
            var cookie = page.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            var view = attribute(VIEW, page.body());
            var csrf = attribute(CSRF, page.body());
            var form = "_view=" + encode(view) + "&_csrf=" + encode(csrf)
                    + "&_protocol=" + encode(Roots.PROTOCOL_VERSION);

            var secondPage = CLIENT.send(HttpRequest.newBuilder(nodeB.uri()).header("Cookie", cookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, secondPage.statusCode(), secondPage.body());
            assertTrue(secondPage.body().contains("visit 2"), secondPage.body());

            var wrongNode = CLIENT.send(HttpRequest.newBuilder(nodeB.uri().resolve("/_roots/dispose"))
                            .header("Cookie", cookie)
                            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, wrongNode.statusCode(), wrongNode.body());
            assertEquals("jdbc-node-a", wrongNode.headers().firstValue("X-Roots-Owner").orElseThrow());

            var disposed = CLIENT.send(HttpRequest.newBuilder(nodeA.uri().resolve("/_roots/dispose"))
                            .header("Cookie", cookie)
                            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(204, disposed.statusCode(), disposed.body());
        }
    }

    @Test
    void rejectsUnsafeConfigurationAndInvalidLeaseInput() {
        var dataSource = dataSource();
        var now = Instant.parse("2026-08-16T12:00:00Z");
        assertThrows(NullPointerException.class, () -> new JdbcLiveViewOwnership(null, "node"));
        assertThrows(IllegalArgumentException.class, () -> new JdbcLiveViewOwnership(dataSource, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcLiveViewOwnership(dataSource, "node", "leases; DROP TABLE users"));
        assertThrows(IllegalArgumentException.class, () -> JdbcLiveViewOwnership.schemaStatements("schema.leases"));
        assertThrows(UnsupportedOperationException.class,
                () -> JdbcLiveViewOwnership.schemaStatements().add("unsafe"));

        JdbcLiveViewOwnership.createSchema(dataSource);
        var ownership = new JdbcLiveViewOwnership(dataSource, "node");
        assertThrows(IllegalArgumentException.class,
                () -> ownership.claim(" ", "session", now, now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> ownership.claim("x".repeat(513), "session", now, now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> ownership.claim("view\n", "session", now, now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> ownership.claim("view", " ", now, now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> ownership.claim("view", "session", now, now));
        assertThrows(IllegalArgumentException.class,
                () -> ownership.claim("view", "session", Instant.MIN, Instant.MAX));
        assertThrows(NullPointerException.class, () -> ownership.find("view", null));
    }

    @Test
    void translatesEveryDatabaseOutageToTheOwnershipException() {
        DataSource unavailable = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new SQLException("offline", "08006");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var ownership = new JdbcLiveViewOwnership(unavailable, "node-a");
        var now = Instant.parse("2026-08-16T12:00:00Z");

        assertDatabaseFailure(() -> JdbcLiveViewOwnership.createSchema(unavailable));
        assertDatabaseFailure(() -> ownership.claim("view", "session", now, now.plusSeconds(1)));
        assertDatabaseFailure(() -> ownership.renew("view", "session", now, now.plusSeconds(1)));
        assertDatabaseFailure(() -> ownership.find("view", now));
        assertDatabaseFailure(() -> ownership.release("view", "session"));
        assertDatabaseFailure(() -> ownership.deleteExpired(now));
    }

    @Test
    void rejectsBrokenDriverResultsAndDistinguishesConstraintFailures() {
        var now = Instant.parse("2026-08-16T12:00:00Z");

        var zeroInsert = new JdbcLiveViewOwnership(updateDataSource(0, 0), "node-a");
        var missingInsert = assertThrows(LiveViewOwnershipException.class,
                () -> zeroInsert.claim("view", "session", now, now.plusSeconds(1)));
        assertTrue(missingInsert.getMessage().contains("insert did not create exactly one row"));

        var excessiveUpdate = new JdbcLiveViewOwnership(updateDataSource(2), "node-a");
        var excessive = assertThrows(LiveViewOwnershipException.class,
                () -> excessiveUpdate.claim("view", "session", now, now.plusSeconds(1)));
        assertTrue(excessive.getMessage().contains("invalid row count"));

        var ordinarySqlFailure = new JdbcLiveViewOwnership(
                updateDataSource(0, new SQLException("offline", "08006")), "node-a");
        assertDatabaseFailure(() -> ordinarySqlFailure.claim("view", "session", now, now.plusSeconds(1)));

        var specializedConstraint = new JdbcLiveViewOwnership(
                updateDataSource(0, new SQLIntegrityConstraintViolationException("duplicate"), 0), "node-a");
        assertFalse(specializedConstraint.claim("view", "session", now, now.plusSeconds(1)));

        var duplicateRows = new JdbcLiveViewOwnership(duplicateOwnerDataSource(now.plusSeconds(1)), "node-a");
        var duplicate = assertThrows(LiveViewOwnershipException.class,
                () -> duplicateRows.find("view", now));
        assertTrue(duplicate.getMessage().contains("duplicate rows"));
    }

    @Test
    void publicApiIsExactAndDeliberatelySmall() {
        var constructors = Set.of(JdbcLiveViewOwnership.class.getConstructors()).stream()
                .map(constructor -> descriptor("new", constructor.getParameterTypes()))
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "new(javax.sql.DataSource,java.lang.String)",
                "new(javax.sql.DataSource,java.lang.String,java.lang.String)"
        ), constructors);

        var methods = Set.of(JdbcLiveViewOwnership.class.getDeclaredMethods()).stream()
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> descriptor(method.getName(), method.getParameterTypes()))
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "schemaStatements()", "schemaStatements(java.lang.String)",
                "createSchema(javax.sql.DataSource)", "createSchema(javax.sql.DataSource,java.lang.String)",
                "localNodeId()", "claim(java.lang.String,java.lang.String,java.time.Instant,java.time.Instant)",
                "renew(java.lang.String,java.lang.String,java.time.Instant,java.time.Instant)",
                "find(java.lang.String,java.time.Instant)", "release(java.lang.String,java.lang.String)",
                "deleteExpired(java.time.Instant)"
        ), methods);
        assertEquals(Set.of("DEFAULT_TABLE"), Set.of(JdbcLiveViewOwnership.class.getFields()).stream()
                .map(field -> field.getName()).collect(Collectors.toSet()));
    }

    private static JdbcDataSource dataSource() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:roots_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        return dataSource;
    }

    private static DataSource updateDataSource(Object... executions) {
        var scripted = new ArrayDeque<>(java.util.Arrays.asList(executions));
        return proxy(DataSource.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getConnection" -> proxy(java.sql.Connection.class,
                    (connection, connectionMethod, connectionArguments) -> switch (connectionMethod.getName()) {
                        case "prepareStatement" -> proxy(java.sql.PreparedStatement.class,
                                (statement, statementMethod, statementArguments) -> switch (statementMethod.getName()) {
                                    case "executeUpdate" -> {
                                        var result = scripted.removeFirst();
                                        if (result instanceof SQLException exception) {
                                            throw exception;
                                        }
                                        yield result;
                                    }
                                    case "close", "setLong", "setString" -> null;
                                    default -> defaultValue(statementMethod.getReturnType());
                                });
                        case "close" -> null;
                        default -> defaultValue(connectionMethod.getReturnType());
                    });
            default -> defaultValue(method.getReturnType());
        });
    }

    private static DataSource duplicateOwnerDataSource(Instant expiresAt) {
        return proxy(DataSource.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getConnection" -> proxy(java.sql.Connection.class,
                    (connection, connectionMethod, connectionArguments) -> switch (connectionMethod.getName()) {
                        case "prepareStatement" -> proxy(java.sql.PreparedStatement.class,
                                (statement, statementMethod, statementArguments) -> switch (statementMethod.getName()) {
                                    case "executeQuery" -> {
                                        var calls = new int[1];
                                        yield proxy(java.sql.ResultSet.class,
                                                (result, resultMethod, resultArguments) -> switch (resultMethod.getName()) {
                                                    case "next" -> ++calls[0] <= 2;
                                                    case "getString" -> (int) resultArguments[0] == 1 ? "node-a" : "session";
                                                    case "getLong" -> expiresAt.toEpochMilli();
                                                    case "close" -> null;
                                                    default -> defaultValue(resultMethod.getReturnType());
                                                });
                                    }
                                    case "close", "setLong", "setString" -> null;
                                    default -> defaultValue(statementMethod.getReturnType());
                                });
                        case "close" -> null;
                        default -> defaultValue(connectionMethod.getReturnType());
                    });
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static void assertDatabaseFailure(Runnable operation) {
        var exception = assertThrows(LiveViewOwnershipException.class, operation::run);
        assertTrue(exception.getCause() instanceof SQLException, exception.toString());
    }

    private static String descriptor(String name, Class<?>[] parameters) {
        return name + java.util.Arrays.stream(parameters)
                .map(Class::getName)
                .collect(Collectors.joining(",", "(", ")"));
    }

    private static String attribute(Pattern pattern, String body) {
        var matcher = pattern.matcher(body);
        assertTrue(matcher.find(), body);
        return matcher.group(1);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Attempt(String nodeId, boolean claimed) {
    }
}
