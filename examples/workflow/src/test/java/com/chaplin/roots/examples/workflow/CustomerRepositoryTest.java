package com.chaplin.roots.examples.workflow;

import com.chaplin.roots.AuthenticatedIdentity;
import com.chaplin.roots.ValidationException;
import com.chaplin.roots.examples.workflow.CustomerRepository.Fields;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class CustomerRepositoryTest {
    static final AuthenticatedIdentity EDITOR = new AuthenticatedIdentity("editor", Set.of("ROLE_EDITOR"));
    static final AuthenticatedIdentity OTHER = new AuthenticatedIdentity("another-editor", Set.of("ROLE_EDITOR"));
    static final AuthenticatedIdentity VIEWER = new AuthenticatedIdentity("viewer", Set.of("ROLE_VIEWER"));
    static final Fields VALID = new Fields("Northstar Freight", "Mina Patel", "mina@example.com");
    @TempDir Path temp;

    static HikariDataSource pool(String url, boolean migrate) {
        var config = new HikariConfig(); config.setJdbcUrl(url); config.setMaximumPoolSize(8);
        if (url.startsWith("jdbc:postgresql:")) {
            config.setUsername(System.getenv("ROOTS_TEST_POSTGRES_USER"));
            config.setPassword(System.getenv("ROOTS_TEST_POSTGRES_PASSWORD"));
        }
        config.setConnectionTimeout(2000); config.setValidationTimeout(1000);
        var pool = new HikariDataSource(config);
        if (migrate) Application.migrations(pool).migrate();
        return pool;
    }
    String url() { return "jdbc:h2:file:" + temp.resolve("workflow").toAbsolutePath().toString().replace('\\', '/') + ";LOCK_TIMEOUT=5000"; }

    @Test void durableDraftAndExactlyOneBusinessCommitSurviveReopenAndConcurrentReplay() throws Exception {
        var url = url(); UUID draftId; UUID customerId;
        try (var pool = pool(url, true)) {
            var repository = new CustomerRepository(pool);
            var draft = repository.create(EDITOR, null);
            draft = repository.save(EDITOR, draft.id(), 0, VALID);
            draftId = draft.id();
        }
        try (var pool = pool(url, false); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var repository = new CustomerRepository(pool);
            var draft = repository.draft(EDITOR, draftId);
            assertEquals(VALID, draft.fields());
            var results = executor.invokeAll(java.util.stream.IntStream.range(0, 64)
                    .<java.util.concurrent.Callable<UUID>>mapToObj(i -> () -> repository.complete(EDITOR, draftId, draft.version())).toList());
            customerId = results.getFirst().get();
            for (var result : results) assertEquals(customerId, result.get());
            assertEquals(1, count(pool, "workflow_customers")); assertEquals(1, count(pool, "workflow_audit"));
        }
        try (var pool = pool(url, false)) {
            var repository = new CustomerRepository(pool);
            assertTrue(repository.draft(EDITOR, draftId).completed());
            assertEquals(customerId, repository.complete(EDITOR, draftId, 1));
            assertThrows(CustomerRepository.Conflict.class, () -> repository.complete(EDITOR, draftId, -100));
            assertEquals(VALID, repository.customer(VIEWER, customerId).fields());
        }
    }

    @Test void concurrentEditorsMustReviewExactSavedVersionAndKeepTheirDurableDraft() {
        try (var pool = pool(url(), true)) {
            var repository = new CustomerRepository(pool);
            var seed = repository.create(EDITOR, null);
            seed = repository.save(EDITOR, seed.id(), seed.version(), VALID);
            var id = repository.complete(EDITOR, seed.id(), seed.version());
            var first = repository.create(EDITOR, id);
            var second = repository.create(OTHER, id);
            first = repository.save(EDITOR, first.id(), first.version(), new Fields("First edit", "First", "first@example.com"));
            repository.complete(EDITOR, first.id(), first.version());
            second = repository.save(OTHER, second.id(), second.version(), new Fields("Second edit", "Second", "second@example.com"));
            var conflictDraft = second;
            assertThrows(CustomerRepository.Conflict.class, () -> repository.complete(OTHER, conflictDraft.id(), conflictDraft.version()));
            assertEquals("Second edit", repository.draft(OTHER, second.id()).fields().company());
            assertEquals("First edit", repository.customer(VIEWER, id).fields().company());
            assertEquals(2, count(pool, "workflow_audit"));
            assertThrows(CustomerRepository.Conflict.class, () -> repository.review(OTHER, conflictDraft.id(), conflictDraft.version(), 1));
            second = repository.review(OTHER, second.id(), second.version(), 2);
            repository.complete(OTHER, second.id(), second.version());
            assertEquals(3, repository.customer(VIEWER, id).version());
        }
    }

    @Test void ownershipRolesValidationAndStaleDraftsAreEnforcedBelowTheUi() {
        try (var pool = pool(url(), true)) {
            var repository = new CustomerRepository(pool);
            var draft = repository.create(EDITOR, null);
            assertThrows(SecurityException.class, () -> repository.create(VIEWER, null));
            assertThrows(SecurityException.class, () -> repository.customers(AuthenticatedIdentity.named("nobody"), "", ""));
            assertThrows(CustomerRepository.Missing.class, () -> repository.draft(OTHER, draft.id()));
            assertThrows(CustomerRepository.Missing.class, () -> repository.save(OTHER, draft.id(), 0, VALID));
            assertThrows(CustomerRepository.Missing.class, () -> repository.complete(OTHER, draft.id(), 0));
            var partial = repository.save(EDITOR, draft.id(), 0, new Fields("Pending", "", "invalid"));
            assertThrows(ValidationException.class, () -> repository.complete(EDITOR, partial.id(), partial.version()));
            assertEquals("Pending", repository.draft(EDITOR, draft.id()).fields().company());
            assertThrows(CustomerRepository.Conflict.class, () -> repository.save(EDITOR, draft.id(), 0, VALID));
            assertEquals(0, count(pool, "workflow_customers")); assertEquals(0, count(pool, "workflow_audit"));
            assertThrows(ValidationException.class, () -> new Fields("x".repeat(121), "", ""));
        }
    }

    @Test void auditFailureRollsBackCustomerAndCompletionMarkerTogether() throws Exception {
        try (var pool = pool(url(), true)) {
            var repository = new CustomerRepository(pool);
            var draft = repository.create(EDITOR, null);
            draft = repository.save(EDITOR, draft.id(), 0, VALID);
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE workflow_audit ADD CONSTRAINT reject_audit CHECK (customer_version < 0)");
            }
            var saved = draft;
            assertThrows(IllegalStateException.class, () -> repository.complete(EDITOR, saved.id(), saved.version()));
            assertEquals(0, count(pool, "workflow_customers")); assertEquals(0, count(pool, "workflow_audit"));
            assertFalse(repository.draft(EDITOR, draft.id()).completed());
            assertEquals(VALID, repository.draft(EDITOR, draft.id()).fields());
        }
    }

    @Test void paginationIsBoundedOrderedAndEscapesSqlWildcards() {
        try (var pool = pool(url(), true)) {
            var repository = new CustomerRepository(pool);
            for (int i = 0; i < 61; i++) {
                var draft = repository.create(EDITOR, null);
                draft = repository.save(EDITOR, draft.id(), 0, new Fields("Company " + i, "Contact", "contact@example.com"));
                repository.complete(EDITOR, draft.id(), draft.version());
            }
            var seen = new HashSet<UUID>(); String cursor = ""; var sizes = new ArrayList<Integer>();
            do {
                var page = repository.customers(VIEWER, "COMPANY", cursor);
                sizes.add(page.items().size());
                page.items().forEach(c -> assertTrue(seen.add(c.id())));
                cursor = page.next();
            } while (cursor != null);
            assertEquals(java.util.List.of(25, 25, 11), sizes); assertEquals(61, seen.size());
            assertTrue(repository.customers(VIEWER, "%", "").items().isEmpty());
            assertTrue(repository.customers(VIEWER, "' OR 1=1 --", "").items().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> repository.customers(VIEWER, "", "broken"));
        }
    }

    static long count(HikariDataSource pool, String table) {
        if (!Set.of("workflow_customers", "workflow_audit", "workflow_drafts").contains(table)) throw new IllegalArgumentException();
        try (var connection = pool.getConnection(); var query = connection.createStatement(); var result = query.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(result.next()); return result.getLong(1);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
