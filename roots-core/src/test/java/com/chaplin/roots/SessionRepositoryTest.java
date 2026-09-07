package com.chaplin.roots;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionRepositoryTest {
    @Test
    void inMemoryRepositoryRefreshesExpiryAndRecoversCapacity() {
        var repository = SessionRepository.inMemory();
        var now = Instant.parse("2026-08-16T12:00:00Z");
        var session = repository.create(now.plusSeconds(10), 1).orElseThrow();

        session.put("user", "Ada");
        assertTrue(repository.create(now.plusSeconds(10), 1).isEmpty());
        assertEquals("Ada", repository.findAndTouch(
                session.id(), now.plusSeconds(5), now.plusSeconds(20)
        ).orElseThrow().get("user").orElseThrow());

        repository.deleteExpired(now.plusSeconds(10));
        assertEquals(1, repository.size());
        repository.deleteExpired(now.plusSeconds(20));
        assertEquals(0, repository.size());
        assertTrue(repository.create(now.plusSeconds(30), 1).isPresent());

        repository.close();
        assertEquals(0, repository.size());
    }

    @Test
    void inMemoryRepositoryEnforcesCapacityUnderConcurrentCreation() throws Exception {
        var repository = SessionRepository.inMemory();
        var tasks = new ArrayList<java.util.concurrent.Callable<Boolean>>();
        for (var index = 0; index < 100; index++) {
            tasks.add(() -> repository.create(Instant.now().plusSeconds(60), 5).isPresent());
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var created = executor.invokeAll(tasks).stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .count();
            assertEquals(5, created);
            assertEquals(5, repository.size());
        }
    }

    @Test
    void validatesRepositoryArgumentsAndCookieSafeIdentifiers() {
        var repository = SessionRepository.inMemory();
        var now = Instant.now();
        var session = repository.create(now.plusSeconds(1), 1).orElseThrow();

        assertThrows(IllegalArgumentException.class,
                () -> repository.findAndTouch(session.id(), now, now));
        assertThrows(IllegalArgumentException.class,
                () -> repository.create(now, 0));
        assertThrows(IllegalArgumentException.class, () -> new Session("bad;id"));
        assertThrows(IllegalArgumentException.class, () -> new Session("bad\nid"));
        assertThrows(IllegalArgumentException.class, () -> new Session("bad\"id"));
        assertThrows(IllegalArgumentException.class, () -> new Session("bad\\id"));
        assertThrows(IllegalArgumentException.class, () -> new Session("snowman-☃"));
        assertFalse(session.id().contains("="));
        assertTrue(session.id().length() >= 32);
    }
}
