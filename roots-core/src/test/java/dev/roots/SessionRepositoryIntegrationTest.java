package dev.roots;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionRepositoryIntegrationTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void runtimeUsesAPluggableRepositoryWithoutOwningSharedData() throws Exception {
        var repository = new RecordingRepository();
        var application = Roots.start(RootsConfig.forApplication(dev.roots.manifestapp.Application.class)
                .port(0)
                .development(false)
                .sessionRepository(repository)
                .build());
        try {
            var first = CLIENT.send(
                    HttpRequest.newBuilder(application.uri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            var cookie = first.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
            var second = CLIENT.send(
                    HttpRequest.newBuilder(application.uri()).header("Cookie", cookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, first.statusCode());
            assertEquals(200, second.statusCode());
            assertTrue(cookie.startsWith("ROOTS_SESSION=external-session-1"));
            assertEquals(1, repository.creates.get());
            assertEquals(1, repository.finds.get());
            assertEquals(1, application.runtimeSnapshot().sessions());
        } finally {
            application.close();
        }
        assertTrue(repository.closed);
    }

    @Test
    void repositoryAvailabilityFailuresBecomeSessionFreeServiceUnavailableResponses() throws Exception {
        try (var application = Roots.start(RootsConfig.forApplication(dev.roots.manifestapp.Application.class)
                .port(0)
                .development(false)
                .sessionRepository(new UnavailableRepository())
                .build())) {
            var response = CLIENT.send(
                    HttpRequest.newBuilder(application.uri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(503, response.statusCode());
            assertTrue(response.body().contains("Session repository is unavailable"));
            assertTrue(response.headers().firstValue("set-cookie").isEmpty());
            assertEquals(0, application.runtimeSnapshot().sessions());
            assertEquals(1, application.runtimeSnapshot().rejectedRequests());
        }
    }

    @Test
    void malformedSessionCookieNeverReachesAnExternalRepository() throws Exception {
        var repository = new RecordingRepository();
        try (var application = Roots.start(RootsConfig.forApplication(dev.roots.manifestapp.Application.class)
                .port(0)
                .development(false)
                .sessionRepository(repository)
                .build())) {
            var response = CLIENT.send(
                    HttpRequest.newBuilder(application.uri())
                            .header("Cookie", "ROOTS_SESSION=bad\"identifier")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            assertEquals(0, repository.finds.get());
            assertEquals(1, repository.creates.get());
            assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow()
                    .startsWith("ROOTS_SESSION=external-session-1;"));
        }
    }

    private static final class RecordingRepository implements SessionRepository {
        private final AtomicInteger creates = new AtomicInteger();
        private final AtomicInteger finds = new AtomicInteger();
        private volatile Session session;
        private volatile boolean closed;

        @Override
        public Optional<Session> findAndTouch(String id, Instant accessedAt, Instant expiresAt) {
            finds.incrementAndGet();
            return session != null && session.id().equals(id) ? Optional.of(session) : Optional.empty();
        }

        @Override
        public Optional<Session> create(Instant expiresAt, int maximumSessions) {
            creates.incrementAndGet();
            session = new Session("external-session-1");
            return Optional.of(session);
        }

        @Override
        public void deleteExpired(Instant now) {
        }

        @Override
        public int size() {
            return session == null ? 0 : 1;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class UnavailableRepository implements SessionRepository {
        @Override
        public Optional<Session> findAndTouch(String id, Instant accessedAt, Instant expiresAt) {
            throw new SessionRepositoryException("offline");
        }

        @Override
        public Optional<Session> create(Instant expiresAt, int maximumSessions) {
            throw new SessionRepositoryException("offline");
        }

        @Override
        public void deleteExpired(Instant now) {
        }

        @Override
        public int size() {
            return 0;
        }
    }
}
