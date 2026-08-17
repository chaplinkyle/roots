package dev.roots.jdbc;

import dev.roots.SessionRepositoryException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcSessionRepositoryTest {
    @Test
    void sharesLazyValuesAndTheCompleteLifecycleAcrossRepositories() {
        var dataSource = dataSource();
        JdbcSessionRepository.createSchema(dataSource);
        var first = new JdbcSessionRepository(dataSource);
        var second = new JdbcSessionRepository(dataSource);
        var now = Instant.parse("2026-08-16T12:00:00Z");
        var created = first.create(now.plusSeconds(10), 10).orElseThrow();

        created.put("name", "Ada");
        created.put("visits", 1);
        var found = second.findAndTouch(created.id(), now.plusSeconds(1), now.plusSeconds(20)).orElseThrow();
        assertNotSame(created, found);
        assertEquals("Ada", found.get("name", String.class).orElseThrow());
        assertEquals(1, found.get("visits", Integer.class).orElseThrow());
        found.put("visits", 2);
        assertEquals(2, created.get("visits", Integer.class).orElseThrow());
        assertEquals(Map.of("name", "Ada", "visits", 2), found.snapshot());

        found.remove("name");
        found.put("visits", null);
        assertTrue(created.snapshot().isEmpty());
        first.close();
        assertEquals(1, second.size());

        second.deleteExpired(now.plusSeconds(19));
        assertEquals(1, second.size());
        second.deleteExpired(now.plusSeconds(20));
        assertEquals(0, second.size());
        assertTrue(created.snapshot().isEmpty());
        assertTrue(second.findAndTouch(created.id(), now.plusSeconds(20), now.plusSeconds(30)).isEmpty());
    }

    @Test
    void enforcesOneExactCapacityAcrossConcurrentNodesAndRecoversIt() throws Exception {
        var dataSource = dataSource();
        JdbcSessionRepository.createSchema(dataSource);
        var first = new JdbcSessionRepository(dataSource);
        var second = new JdbcSessionRepository(dataSource);
        var expiry = Instant.parse("2026-08-16T12:01:00Z");
        var tasks = new ArrayList<Callable<Boolean>>();
        for (var index = 0; index < 100; index++) {
            var repository = index % 2 == 0 ? first : second;
            tasks.add(() -> repository.create(expiry, 5).isPresent());
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var created = 0;
            for (var future : executor.invokeAll(tasks)) {
                if (future.get()) {
                    created++;
                }
            }
            assertEquals(5, created);
        }
        assertEquals(5, first.size());
        assertTrue(first.create(expiry, 5).isEmpty());
        second.deleteExpired(expiry);
        assertEquals(0, first.size());
        assertTrue(second.create(expiry.plusSeconds(30), 5).isPresent());
    }

    @Test
    void concurrentValueCreationUsesAtomicUpdateOrInsert() throws Exception {
        var dataSource = dataSource();
        JdbcSessionRepository.createSchema(dataSource);
        var first = new JdbcSessionRepository(dataSource);
        var second = new JdbcSessionRepository(dataSource);
        var now = Instant.parse("2026-08-16T12:00:00Z");
        var session = first.create(now.plusSeconds(30), 1).orElseThrow();
        var peer = second.findAndTouch(session.id(), now, now.plusSeconds(30)).orElseThrow();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writes = new ArrayList<Callable<Void>>();
            for (var index = 0; index < 100; index++) {
                var value = index;
                var target = index % 2 == 0 ? session : peer;
                writes.add(() -> {
                    target.put("contended", value);
                    return null;
                });
            }
            for (var write : executor.invokeAll(writes)) {
                write.get();
            }
        }
        assertTrue(session.get("contended", Integer.class).isPresent());
        assertEquals(session.get("contended"), peer.get("contended"));
    }

    @Test
    void standardCodecRoundTripsEverySupportedTypeWithoutJavaSerialization() {
        var codec = JdbcSessionValueCodec.standard();
        var instant = Instant.parse("2026-08-16T12:00:00.123Z");
        var uuid = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        var values = java.util.List.of(
                "Ada λ", true, false, (byte) 3, (short) 4, 5, 6L, 7.5F, 8.5D,
                new BigInteger("12345678901234567890"), new BigDecimal("123.4500"), 'λ', uuid, instant
        );
        for (var value : values) {
            assertEquals(value, codec.decode(codec.encode(value)), value.getClass().getName());
        }
        var bytes = "binary\0value".getBytes(StandardCharsets.UTF_8);
        var decoded = (byte[]) codec.decode(codec.encode(bytes));
        assertArrayEquals(bytes, decoded);
        assertNotSame(bytes, decoded);

        assertThrows(NullPointerException.class, () -> codec.encode(null));
        assertThrows(NullPointerException.class, () -> codec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new Object()));
        for (var malformed : java.util.List.of("", "missing-colon", "bool:maybe", "char:-1",
                "char:65536", "uuid:nope", "unknown:value", "int:nope", "str:*")) {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(malformed), malformed);
        }
    }

    @Test
    void supportsAnApplicationCodecAndCustomTables() {
        record Preference(String theme) { }
        JdbcSessionValueCodec codec = new JdbcSessionValueCodec() {
            @Override
            public String encode(Object value) {
                return ((Preference) value).theme();
            }

            @Override
            public Object decode(String encoded) {
                return new Preference(encoded);
            }
        };
        var dataSource = dataSource();
        JdbcSessionRepository.createSchema(dataSource, "tenant_sessions");
        var repository = new JdbcSessionRepository(dataSource, codec, "tenant_sessions");
        var session = repository.create(Instant.now().plusSeconds(30), 1).orElseThrow();
        session.put("preference", new Preference("dark"));
        assertEquals(new Preference("dark"), session.get("preference").orElseThrow());
    }

    @Test
    void validatesSchemaLifecycleAndBoundedInputs() {
        var dataSource = dataSource();
        var now = Instant.parse("2026-08-16T12:00:00Z");
        assertThrows(NullPointerException.class, () -> new JdbcSessionRepository(null));
        assertThrows(NullPointerException.class, () -> new JdbcSessionRepository(dataSource, null));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcSessionRepository(dataSource, JdbcSessionValueCodec.standard(), "bad.table"));
        assertThrows(UnsupportedOperationException.class,
                () -> JdbcSessionRepository.schemaStatements().add("unsafe"));

        JdbcSessionRepository.createSchema(dataSource);
        assertThrows(SessionRepositoryException.class, () -> JdbcSessionRepository.createSchema(dataSource));
        var repository = new JdbcSessionRepository(dataSource);
        assertThrows(IllegalArgumentException.class, () -> repository.create(now, 0));
        assertThrows(IllegalArgumentException.class, () -> repository.create(Instant.MAX, 1));
        assertThrows(IllegalArgumentException.class, () -> repository.findAndTouch("bad;id", now, now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repository.findAndTouch("valid", now, now));
        var session = repository.create(now.plusSeconds(10), 1).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> session.put(" ", "value"));
        assertThrows(IllegalArgumentException.class, () -> session.put("x".repeat(256), "value"));
        assertThrows(IllegalArgumentException.class, () -> session.put("bad\nkey", "value"));
        assertThrows(IllegalArgumentException.class, () -> session.put("unsupported", new Object()));
        JdbcSessionValueCodec huge = new JdbcSessionValueCodec() {
            public String encode(Object value) { return "x".repeat(JdbcSessionRepository.MAX_ENCODED_VALUE_LENGTH + 1); }
            public Object decode(String encoded) { return encoded; }
        };
        var hugeRepository = new JdbcSessionRepository(dataSource, huge);
        var found = hugeRepository.findAndTouch(session.id(), now, now.plusSeconds(10)).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> found.put("huge", "value"));
    }

    @Test
    void translatesRepositoryAndLazyValueOutages() {
        var actual = dataSource();
        JdbcSessionRepository.createSchema(actual);
        var offline = new AtomicBoolean();
        DataSource switching = switching(actual, offline);
        var repository = new JdbcSessionRepository(switching);
        var now = Instant.parse("2026-08-16T12:00:00Z");
        var session = repository.create(now.plusSeconds(10), 1).orElseThrow();
        session.put("value", "online");
        offline.set(true);

        assertDatabaseFailure(() -> JdbcSessionRepository.createSchema(switching));
        assertDatabaseFailure(() -> repository.findAndTouch(session.id(), now, now.plusSeconds(10)));
        assertDatabaseFailure(() -> repository.create(now.plusSeconds(10), 1));
        assertDatabaseFailure(() -> repository.deleteExpired(now));
        assertDatabaseFailure(repository::size);
        assertDatabaseFailure(() -> session.get("value"));
        assertDatabaseFailure(() -> session.put("value", "offline"));
        assertDatabaseFailure(() -> session.remove("value"));
        assertDatabaseFailure(session::snapshot);
    }

    @Test
    void publicSessionApiIsExact() {
        assertTrue(Modifier.isPublic(JdbcSessionRepository.class.getModifiers()));
        assertTrue(Modifier.isFinal(JdbcSessionRepository.class.getModifiers()));
        var constructors = Set.of(JdbcSessionRepository.class.getConstructors()).stream()
                .map(constructor -> descriptor("new", constructor.getParameterTypes()))
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "new(javax.sql.DataSource)",
                "new(javax.sql.DataSource,dev.roots.jdbc.JdbcSessionValueCodec)",
                "new(javax.sql.DataSource,dev.roots.jdbc.JdbcSessionValueCodec,java.lang.String)"
        ), constructors);
        var methods = Set.of(JdbcSessionRepository.class.getDeclaredMethods()).stream()
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> descriptor(method.getName(), method.getParameterTypes()))
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "schemaStatements()", "schemaStatements(java.lang.String)",
                "createSchema(javax.sql.DataSource)", "createSchema(javax.sql.DataSource,java.lang.String)",
                "findAndTouch(java.lang.String,java.time.Instant,java.time.Instant)",
                "create(java.time.Instant,int)", "deleteExpired(java.time.Instant)", "size()"
        ), methods);
        assertEquals(Set.of("DEFAULT_TABLE", "MAX_ENCODED_VALUE_LENGTH"),
                Set.of(JdbcSessionRepository.class.getFields()).stream()
                        .map(field -> field.getName()).collect(Collectors.toSet()));

        assertTrue(Modifier.isPublic(JdbcSessionValueCodec.class.getModifiers()));
        assertTrue(Modifier.isInterface(JdbcSessionValueCodec.class.getModifiers()));
        assertEquals(Set.of("encode(java.lang.Object)", "decode(java.lang.String)", "standard()"),
                Set.of(JdbcSessionValueCodec.class.getDeclaredMethods()).stream()
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(method -> descriptor(method.getName(), method.getParameterTypes()))
                        .collect(Collectors.toSet()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PostgreSQL", "MySQL", "MariaDB", "Oracle", "MSSQLServer"})
    void schemaAndCoreOperationsSurviveCommonCompatibilityModes(String mode) {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:roots_mode_" + mode + "_" + UUID.randomUUID()
                + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        JdbcSessionRepository.createSchema(dataSource);
        JdbcLiveViewOwnership.createSchema(dataSource);
        var now = Instant.parse("2026-08-16T12:00:00Z");
        var sessions = new JdbcSessionRepository(dataSource);
        var session = sessions.create(now.plusSeconds(10), 1).orElseThrow();
        session.put("mode", mode);
        assertEquals(mode, sessions.findAndTouch(session.id(), now, now.plusSeconds(20))
                .orElseThrow().get("mode").orElseThrow());
        var ownership = new JdbcLiveViewOwnership(dataSource, "node-" + mode);
        assertTrue(ownership.claim("view", session.id(), now, now.plusSeconds(10)));
        assertTrue(ownership.renew("view", session.id(), now.plusSeconds(1), now.plusSeconds(20)));
        ownership.release("view", session.id());
        assertTrue(ownership.find("view", now).isEmpty());
    }

    private static JdbcDataSource dataSource() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:roots_sessions_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        return dataSource;
    }

    private static DataSource switching(DataSource actual, AtomicBoolean offline) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) {
                        if (offline.get()) {
                            throw new SQLException("offline", "08006");
                        }
                        return arguments == null || arguments.length == 0
                                ? actual.getConnection()
                                : actual.getConnection((String) arguments[0], (String) arguments[1]);
                    }
                    return method.invoke(actual, arguments);
                });
    }

    private static void assertDatabaseFailure(Runnable operation) {
        var exception = assertThrows(SessionRepositoryException.class, operation::run);
        assertTrue(exception.getCause() instanceof SQLException, exception.toString());
    }

    private static String descriptor(String name, Class<?>[] parameters) {
        return name + Arrays.stream(parameters)
                .map(Class::getName)
                .collect(Collectors.joining(",", "(", ")"));
    }
}
