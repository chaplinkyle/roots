package com.chaplin.roots;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StatelessApiTest {
    @Test
    void runsAuthenticatedMachineRequestsWithoutDependingOnSessionStorage() throws Exception {
        var repositoryCalls = new AtomicInteger();
        var middlewareCalls = new AtomicInteger();
        var repository = new SessionRepository() {
            @Override public Optional<Session> create(Instant expiresAt, int maximum) {
                repositoryCalls.incrementAndGet(); throw new SessionRepositoryException("offline");
            }
            @Override public Optional<Session> findAndTouch(String id, Instant accessedAt, Instant expiresAt) {
                repositoryCalls.incrementAndGet(); throw new SessionRepositoryException("offline");
            }
            @Override public void deleteExpired(Instant now) { }
            @Override public int size() { return 0; }
        };
        try (var application = Roots.start(RootsConfig.forApplication(com.chaplin.roots.statelessapp.Application.class)
                .port(0).development(false).sessionRepository(repository)
                .authenticationProvider(AuthenticationProvider.bearer(token -> token.equals("machine-token")
                        ? Optional.of(AuthenticatedIdentity.of("automation", java.util.Set.of("write"))) : Optional.empty()))
                .authorize("authenticated", AuthorizationPolicy.authenticated(Response.text(401, "Authentication required")))
                .use((request, chain) -> { middlewareCalls.incrementAndGet(); return chain.next(); })
                .build()); var client = HttpClient.newHttpClient()) {
            var url = application.uri().resolve("/api/items");
            for (int index = 0; index < 20; index++) {
                var response = client.send(HttpRequest.newBuilder(url).header("Authorization", "Bearer machine-token")
                        .header("Cookie", "ROOTS_SESSION=ignored-old-session").build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("automation", response.body());
                assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
                assertTrue(response.headers().firstValue("traceparent").isPresent());
            }
            assertEquals(401, client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var mutation = client.send(HttpRequest.newBuilder(url).header("Authorization", "Bearer machine-token")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(500, mutation.statusCode(), "Session use must fail instead of silently losing data");
            assertEquals(0, repositoryCalls.get());
            assertEquals(22, middlewareCalls.get());
            assertEquals(0, application.runtimeSnapshot().sessions());
            assertEquals(0, application.runtimeSnapshot().liveViews());
        }
    }
}
