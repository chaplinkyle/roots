package com.chaplin.roots;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthorizationIntegrationTest {
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");
    private static final Pattern ACTION = Pattern.compile("data-roots-on-click=\"([^\"]+)\"");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Test
    void annotationsProtectPagesLayoutsApisAndActionsWithoutMutatingDeniedState() throws Exception {
        var trace = new CopyOnWriteArrayList<String>();
        try (var application = Roots.start(RootsConfig.forApplication(com.chaplin.roots.authapp.Application.class)
                .port(0)
                .development(false)
                .authorize("staff", request -> authorizeFlag(trace, "staff", request, "role", "staff"))
                .authorize("write", request -> authorizeFlag(trace, "write", request, "write", true))
                .authorize("project", request -> {
                    trace.add(trace("project", request));
                    var allowed = request.session().get("project", String.class)
                            .filter(project -> project.equals(request.parameters().get("projectId")))
                            .isPresent();
                    return allowed ? Optional.empty() : denied("project", request);
                })
                .mapException(IllegalStateException.class,
                        (request, failure) -> Response.json(500, "{\"error\":\"" + failure.getMessage() + "\"}"))
                .build())) {
            var deniedPage = get(application.uri(), "/admin", null);
            assertEquals(403, deniedPage.statusCode());
            assertTrue(deniedPage.body().contains("\"policy\":\"staff\""), deniedPage.body());
            assertEquals("no-store", deniedPage.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(0, application.runtimeSnapshot().liveViews());
            var cookie = deniedPage.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

            assertEquals(403, get(application.uri(), "/api/admin", cookie).statusCode());
            assertEquals(403, get(application.uri(), "/reports", cookie).statusCode());
            assertEquals(403, get(application.uri(), "/projects/alpha", cookie).statusCode());
            assertEquals(0, application.runtimeSnapshot().liveViews());

            var unknownAction = get(application.uri(), "/unknown-action", cookie);
            assertEquals(500, unknownAction.statusCode());
            assertEquals(0, application.runtimeSnapshot().liveViews());

            assertEquals(204, get(application.uri(), "/api/login?role=staff&project=alpha", cookie).statusCode());

            var admin = get(application.uri(), "/admin", cookie);
            assertEquals(200, admin.statusCode());
            assertTrue(admin.body().contains("Count 0"), admin.body());
            assertEquals(200, get(application.uri(), "/api/admin", cookie).statusCode());
            assertTrue(get(application.uri(), "/reports", cookie).body().contains("data-authorized-layout=\"true\""));
            assertEquals(200, get(application.uri(), "/projects/alpha", cookie).statusCode());
            assertEquals(403, get(application.uri(), "/projects/beta", cookie).statusCode());

            var credentials = credentials(admin, cookie);
            trace.clear();
            var deniedAction = action(application.uri(), credentials);
            assertEquals(403, deniedAction.statusCode());
            assertTrue(deniedAction.body().contains("\"policy\":\"write\""), deniedAction.body());
            assertEquals(List.of(
                    "staff:/admin:/_roots/action:{}",
                    "write:/admin:/_roots/action:{}"
            ), trace);

            assertEquals(204, get(
                    application.uri(),
                    "/api/login?role=staff&project=alpha&write=true",
                    cookie
            ).statusCode());
            trace.clear();
            var allowedAction = action(application.uri(), credentials);
            assertEquals(200, allowedAction.statusCode());
            assertTrue(allowedAction.body().contains("Count 1"), allowedAction.body());
            assertTrue(allowedAction.body().contains("\"revision\":2"), allowedAction.body());
            assertEquals(List.of(
                    "staff:/admin:/_roots/action:{}",
                    "write:/admin:/_roots/action:{}"
            ), trace);

            assertEquals(204, get(application.uri(), "/api/login?write=true", cookie).statusCode());
            trace.clear();
            var classDeniedAction = action(application.uri(), credentials);
            assertEquals(403, classDeniedAction.statusCode());
            assertTrue(classDeniedAction.body().contains("\"policy\":\"staff\""), classDeniedAction.body());
            assertEquals(List.of("staff:/admin:/_roots/action:{}"), trace);
            assertFalse(classDeniedAction.body().contains("Count 2"), classDeniedAction.body());

            trace.clear();
            var deniedStream = CLIENT.send(HttpRequest.newBuilder(application.uri().resolve(
                            "/_roots/stream?view=" + encode(credentials.view())
                                    + "&csrf=" + encode(credentials.csrf())
                                    + "&protocol=" + encode(Roots.PROTOCOL_VERSION)))
                    .header("Cookie", cookie)
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, deniedStream.statusCode());
            assertEquals(List.of("staff:/admin:/_roots/stream:{}"), trace);
        }
    }

    @Test
    void dynamicRouteParametersReachPoliciesAndMissingPoliciesFailAtStartup() throws Exception {
        var parametersSeen = new CopyOnWriteArrayList<String>();
        try (var application = Roots.start(RootsConfig.forApplication(com.chaplin.roots.authapp.Application.class)
                .port(0)
                .authorize("staff", request -> Optional.empty())
                .authorize("write", request -> Optional.empty())
                .authorize("project", request -> {
                    parametersSeen.add(request.parameters().get("projectId"));
                    return Optional.empty();
                })
                .build())) {
            assertEquals(200, get(application.uri(), "/projects/north-star", null).statusCode());
            assertEquals(List.of("north-star"), parametersSeen);
        }

        var missing = assertThrows(IllegalStateException.class, () -> Roots.start(
                RootsConfig.forApplication(com.chaplin.roots.badpolicyapp.Application.class).port(0).build()
        ));
        assertTrue(missing.getMessage().contains("No authorization policy named 'missing'"), missing.getMessage());
    }

    @Test
    void authenticatesEmbeddedServerPagesApisAndLiveActionsWithBearerCredentials() throws Exception {
        var requestPaths = new CopyOnWriteArrayList<String>();
        var bearer = AuthenticationProvider.bearer(token -> "roots-token".equals(token)
                ? Optional.of(AuthenticatedIdentity.of("ada", List.of("ROLE_STAFF", "reports:write")))
                : Optional.empty());
        var authenticationRequired = Response.text(401, "Authentication required")
                .withHeader("WWW-Authenticate", "Bearer realm=\"roots-test\"");
        try (var application = Roots.start(RootsConfig.forApplication(com.chaplin.roots.authapp.Application.class)
                .port(0)
                .development(false)
                .authenticationProvider(request -> {
                    requestPaths.add(request.transportPath());
                    return bearer.authenticate(request);
                })
                .authorize("staff", AuthorizationPolicy.role("STAFF", authenticationRequired))
                .authorize("write", AuthorizationPolicy.authority("reports:write", Response.text(403, "Forbidden")))
                .authorize("project", request -> AuthorizationPolicy.allow())
                .build())) {
            assertEquals(401, get(application.uri(), "/admin", null).statusCode());
            assertEquals(401, getWithAuthorization(application.uri(), "/admin", null, "Bearer malformed!")
                    .statusCode());

            var page = getWithAuthorization(application.uri(), "/admin", null, "Bearer roots-token");
            assertEquals(200, page.statusCode());
            var cookie = page.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            var firstCredentials = credentials(page, cookie);
            assertEquals(200, getWithAuthorization(
                    application.uri(), "/api/admin", cookie, "Bearer roots-token").statusCode());

            var stream = CLIENT.send(HttpRequest.newBuilder(application.uri().resolve(
                            "/_roots/stream?view=" + encode(firstCredentials.view())
                                    + "&csrf=" + encode(firstCredentials.csrf())
                                    + "&protocol=" + encode(Roots.PROTOCOL_VERSION)))
                    .header("Cookie", cookie)
                    .header("Authorization", "Bearer roots-token")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, stream.statusCode());
            stream.body().close();

            var allowed = action(application.uri(), firstCredentials, "Bearer roots-token");
            assertEquals(200, allowed.statusCode());
            assertTrue(allowed.body().contains("Count 1"), allowed.body());

            var secondPage = getWithAuthorization(application.uri(), "/admin", cookie, "Bearer roots-token");
            assertEquals(200, secondPage.statusCode());
            var identityChanged = action(application.uri(), credentials(secondPage, cookie), null);
            assertEquals(409, identityChanged.statusCode());
            assertTrue(identityChanged.body().contains("expired"), identityChanged.body());

            assertEquals(List.of(
                    "/admin", "/admin", "/admin", "/api/admin", "/_roots/stream", "/_roots/action", "/admin",
                    "/_roots/action"
            ), requestPaths);
        }
    }

    @Test
    void authenticationFailuresFailClosedButSessionFreeHealthBypassesProviders() throws Exception {
        var calls = new AtomicInteger();
        try (var application = Roots.start(RootsConfig.forApplication(com.chaplin.roots.authapp.Application.class)
                .port(0)
                .development(false)
                .authenticationProvider(request -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("identity service offline");
                })
                .authorize("staff", request -> AuthorizationPolicy.allow())
                .authorize("write", request -> AuthorizationPolicy.allow())
                .authorize("project", request -> AuthorizationPolicy.allow())
                .mapException(IllegalStateException.class,
                        (request, failure) -> Response.text(503, failure.getMessage()))
                .build())) {
            assertEquals(200, get(application.uri(), "/_roots/health", null).statusCode());
            assertEquals(0, calls.get());

            var failed = get(application.uri(), "/admin", null);
            assertEquals(503, failed.statusCode());
            assertEquals("identity service offline", failed.body());
            assertEquals(1, calls.get());
            assertEquals(0, application.runtimeSnapshot().liveViews());
        }
    }

    @Test
    void policyRegistryRejectsInvalidDuplicateAndMutableConfiguration() {
        AuthorizationPolicy allow = request -> Optional.empty();
        assertTrue(AuthorizationPolicy.allow().isEmpty());
        assertEquals(403, AuthorizationPolicy.deny(Response.text(403, "denied")).orElseThrow().status());
        assertThrows(NullPointerException.class, () -> AuthorizationPolicy.deny(null));
        var builder = RootsConfig.forApplication(com.chaplin.roots.authapp.Application.class)
                .authorize("valid.policy", allow);
        assertThrows(IllegalArgumentException.class, () -> builder.authorize("valid.policy", allow));
        assertThrows(IllegalArgumentException.class, () -> builder.authorize("bad policy", allow));
        assertThrows(NullPointerException.class, () -> RootsConfig.forApplication(com.chaplin.roots.authapp.Application.class)
                .authorize("null", null));

        var config = builder
                .authorize("staff", allow)
                .authorize("write", allow)
                .authorize("project", allow)
                .build();
        assertThrows(UnsupportedOperationException.class, () -> config.authorizationPolicies().put("no", allow));
    }

    private static Optional<Response> authorizeFlag(
            List<String> trace,
            String policy,
            Request request,
            String key,
            Object expected
    ) {
        trace.add(trace(policy, request));
        return request.session().get(key)
                .filter(expected::equals)
                .map(ignored -> Optional.<Response>empty())
                .orElseGet(() -> denied(policy, request));
    }

    private static Optional<Response> denied(String policy, Request request) {
        return Optional.of(Response.json(403, "{\"error\":\"Forbidden\",\"policy\":\"" + policy
                + "\",\"path\":\"" + request.path() + "\"}"));
    }

    private static String trace(String policy, Request request) {
        return policy + ":" + request.path() + ":" + request.transportPath() + ":" + request.parameters();
    }

    private static HttpResponse<String> get(URI base, String path, String cookie) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getWithAuthorization(
            URI base,
            String path,
            String cookie,
            String authorization
    ) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).header("Authorization", authorization).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> action(URI base, Credentials credentials) throws Exception {
        return action(base, credentials, null);
    }

    private static HttpResponse<String> action(
            URI base,
            Credentials credentials,
            String authorization
    ) throws Exception {
        var form = "_view=" + encode(credentials.view())
                + "&_csrf=" + encode(credentials.csrf())
                + "&_protocol=" + encode(Roots.PROTOCOL_VERSION)
                + "&_action=" + encode(credentials.action())
                + "&_event=click";
        var builder = HttpRequest.newBuilder(base.resolve("/_roots/action"))
                .header("Cookie", credentials.cookie())
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return CLIENT.send(builder.POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Credentials credentials(HttpResponse<String> response, String cookie) {
        return new Credentials(
                cookie,
                attribute(VIEW, response.body()),
                attribute(CSRF, response.body()),
                attribute(ACTION, response.body())
        );
    }

    private static String attribute(Pattern pattern, String html) {
        var matcher = pattern.matcher(html);
        assertTrue(matcher.find(), () -> "Missing " + pattern + " in " + html);
        return matcher.group(1);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Credentials(String cookie, String view, String csrf, String action) {
    }
}
