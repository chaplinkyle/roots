package dev.roots;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LiveViewOwnershipTest {
    @Test
    void claimsRenewsExpiresAndReleasesOnlyLocalLeases() {
        var first = new InMemoryLiveViewOwnership("node-a");
        var now = Instant.parse("2026-08-16T12:00:00Z");

        assertTrue(first.claim("view-1", "session-a", now, now.plusSeconds(10)));
        assertEquals("node-a", first.find("view-1", now).orElseThrow().nodeId());
        assertTrue(first.renew("view-1", "session-a", now.plusSeconds(5), now.plusSeconds(20)));
        assertTrue(first.find("view-1", now.plusSeconds(15)).isPresent());
        assertFalse(first.find("view-1", now.plusSeconds(20)).isPresent());

        assertTrue(first.claim("view-2", "session-a", now, now.plusSeconds(10)));
        first.release("view-2", "session-a");
        assertTrue(first.find("view-2", now).isEmpty());
        assertFalse(first.renew("missing", "session-a", now, now.plusSeconds(1)));
    }

    @Test
    void oneNodeWinsEveryConcurrentClaim() throws Exception {
        var first = new InMemoryLiveViewOwnership("node-a");
        var second = new InMemoryLiveViewOwnership("node-b");
        var shared = new java.util.concurrent.ConcurrentHashMap<String, LiveViewOwner>();
        var now = Instant.parse("2026-08-16T12:00:00Z");
        LiveViewOwnership a = sharing(first.localNodeId(), shared);
        LiveViewOwnership b = sharing(second.localNodeId(), shared);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (var index = 0; index < 100; index++) {
                var ownership = index % 2 == 0 ? a : b;
                tasks.add(() -> ownership.claim("contended", "session-a", now, now.plusSeconds(30)));
            }
            var results = executor.invokeAll(tasks);
            var winner = shared.get("contended").nodeId();
            for (var index = 0; index < results.size(); index++) {
                assertEquals((index % 2 == 0 ? "node-a" : "node-b").equals(winner), results.get(index).get());
            }
        }
    }

    @Test
    void rejectsInvalidIdentifiersAndLeaseWindows() {
        assertThrows(IllegalArgumentException.class, () -> LiveViewOwnership.inMemory(" "));
        var ownership = LiveViewOwnership.inMemory("node-a");
        var now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> ownership.claim(" ", "session-a", now, now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> ownership.claim("view", " ", now, now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> ownership.claim("view", "session-a", now, now));
        assertThrows(IllegalArgumentException.class, () -> new LiveViewOwner("node-a", "\n", now));
    }

    @Test
    void staleSessionCannotRenewOrReleaseAReplacementLease() {
        var ownership = LiveViewOwnership.inMemory("node-a");
        var now = Instant.parse("2026-08-16T12:00:00Z");

        assertTrue(ownership.claim("view", "session-old", now, now.plusSeconds(1)));
        assertTrue(ownership.claim("view", "session-new", now.plusSeconds(2), now.plusSeconds(12)));
        assertFalse(ownership.renew("view", "session-old", now.plusSeconds(3), now.plusSeconds(13)));
        ownership.release("view", "session-old");

        var current = ownership.find("view", now.plusSeconds(3)).orElseThrow();
        assertEquals("session-new", current.sessionId());
        assertEquals(now.plusSeconds(12), current.expiresAt());
    }

    @Test
    void everyConcurrentLocalRenewalSucceedsAndExpiryNeverMovesBackward() throws Exception {
        var ownership = LiveViewOwnership.inMemory("node-a");
        var now = Instant.parse("2026-08-16T12:00:00Z");
        assertTrue(ownership.claim("view", "session", now, now.plusSeconds(10)));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var renewals = new ArrayList<Callable<Boolean>>();
            for (var index = 1; index <= 100; index++) {
                var extension = index;
                renewals.add(() -> ownership.renew(
                        "view", "session", now.plusMillis(extension), now.plusSeconds(10 + extension)));
            }
            for (var renewal : executor.invokeAll(renewals)) {
                assertTrue(renewal.get());
            }
        }

        assertEquals(now.plusSeconds(110), ownership.find("view", now.plusSeconds(1)).orElseThrow().expiresAt());
        assertTrue(ownership.renew("view", "session", now.plusSeconds(2), now.plusSeconds(20)));
        assertEquals(now.plusSeconds(110), ownership.find("view", now.plusSeconds(2)).orElseThrow().expiresAt());
    }

    private static LiveViewOwnership sharing(String nodeId,
                                             java.util.concurrent.ConcurrentHashMap<String, LiveViewOwner> leases) {
        return new LiveViewOwnership() {
            public String localNodeId() { return nodeId; }
            public boolean claim(String viewId, String sessionId, Instant now, Instant expiresAt) {
                var result = leases.compute(viewId, (ignored, current) -> current == null
                        ? new LiveViewOwner(nodeId, sessionId, expiresAt) : current);
                return result.nodeId().equals(nodeId);
            }
            public boolean renew(String viewId, String sessionId, Instant now, Instant expiresAt) { return false; }
            public java.util.Optional<LiveViewOwner> find(String viewId, Instant now) {
                return java.util.Optional.ofNullable(leases.get(viewId));
            }
            public void release(String viewId, String sessionId) { }
            public void deleteExpired(Instant now) { }
        };
    }
}
