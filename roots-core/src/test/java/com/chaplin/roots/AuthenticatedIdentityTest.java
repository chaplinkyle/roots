package com.chaplin.roots;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthenticatedIdentityTest {
    @Test
    void identityCopiesAuthoritiesAndUsesExactAuthorityAndRoleSemantics() {
        var source = new ArrayList<>(List.of("read:accounts", "ROLE_ADMIN"));
        var identity = AuthenticatedIdentity.of("ada", source);
        source.clear();

        assertEquals("ada", identity.name());
        assertEquals(Set.of("read:accounts", "ROLE_ADMIN"), identity.authorities());
        assertTrue(identity.hasAuthority("read:accounts"));
        assertFalse(identity.hasAuthority("READ:ACCOUNTS"));
        assertTrue(identity.hasRole("ADMIN"));
        assertTrue(identity.hasRole("ROLE_ADMIN"));
        assertFalse(identity.hasRole("USER"));
        assertThrows(UnsupportedOperationException.class, () -> identity.authorities().add("write"));
        assertTrue(AuthenticatedIdentity.named("grace").authorities().isEmpty());
    }

    @Test
    void identityRejectsMalformedData() {
        assertThrows(NullPointerException.class, () -> AuthenticatedIdentity.named(null));
        assertThrows(IllegalArgumentException.class, () -> AuthenticatedIdentity.named(" "));
        assertThrows(NullPointerException.class, () -> AuthenticatedIdentity.of("ada", null));
        assertThrows(NullPointerException.class,
                () -> AuthenticatedIdentity.of("ada", java.util.Arrays.asList("read", null)));
        assertThrows(IllegalArgumentException.class, () -> AuthenticatedIdentity.of("ada", List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> AuthenticatedIdentity.named("a".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> AuthenticatedIdentity.named("ada\nadmin"));
        assertThrows(IllegalArgumentException.class,
                () -> AuthenticatedIdentity.of("ada", List.of("x".repeat(257))));
        assertThrows(IllegalArgumentException.class,
                () -> AuthenticatedIdentity.of("ada", List.of("read\rwrite")));
        assertThrows(IllegalArgumentException.class,
                () -> AuthenticatedIdentity.of("ada", java.util.stream.IntStream.range(0, 257)
                        .mapToObj(index -> "authority-" + index).toList()));
    }

    @Test
    void identityFlowsThroughRequestPageContextAndActionEvent() {
        var identity = AuthenticatedIdentity.of("ada", Set.of("ROLE_ADMIN"));
        var session = new Session("identity-session");
        var request = new Request(
                "get", "/admin", "/admin", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                new byte[0], session, RootsCache.disabled(), Optional.of(identity)
        );

        assertEquals(Optional.of(identity), request.identity());
        assertEquals(Optional.of(identity), request.withParameters(Map.of("id", "1")).identity());
        assertEquals(Optional.of(identity), request.withLogicalPath("/logical").identity());
        assertEquals(Optional.of(identity),
                request.withLogicalRoute("/logical", Map.of("id", "1"), Map.of("q", List.of("x"))).identity());

        var context = new PageContext(
                "/admin", Map.of(), Map.of(), session, RootsCache.disabled(), Optional.of(identity)
        );
        var event = new ActionEvent(
                BrowserEvent.empty(BrowserEvent.Type.CLICK), Map.of(), Map.of(), session,
                RootsCache.disabled(), Optional.of(identity)
        );
        assertEquals(Optional.of(identity), context.identity());
        assertEquals(Optional.of(identity), event.identity());
    }

    @Test
    void builtInAuthorizationPoliciesHandleAnonymousAuthoritiesAndRoles() throws Exception {
        var denied = Response.json(403, "{\"error\":\"forbidden\"}");
        var anonymous = request(Optional.empty());
        var user = request(Optional.of(AuthenticatedIdentity.of("user", Set.of("ROLE_USER", "read"))));
        var admin = request(Optional.of(AuthenticatedIdentity.of("admin", Set.of("ROLE_ADMIN", "read"))));

        assertTrue(AuthorizationPolicy.authenticated(denied).authorize(anonymous).isPresent());
        assertTrue(AuthorizationPolicy.authenticated(denied).authorize(user).isEmpty());
        assertTrue(AuthorizationPolicy.authority("read", denied).authorize(user).isEmpty());
        assertTrue(AuthorizationPolicy.authority("write", denied).authorize(user).isPresent());
        assertTrue(AuthorizationPolicy.role("ADMIN", denied).authorize(user).isPresent());
        assertTrue(AuthorizationPolicy.role("ADMIN", denied).authorize(admin).isEmpty());
        assertThrows(NullPointerException.class, () -> AuthorizationPolicy.authenticated(null));
        assertThrows(NullPointerException.class, () -> AuthorizationPolicy.authority(null, denied));
        assertThrows(NullPointerException.class, () -> AuthorizationPolicy.role("ADMIN", null));
    }

    private static Request request(Optional<AuthenticatedIdentity> identity) {
        return new Request(
                "GET", "/", "/", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), new byte[0],
                new Session("policy-" + identity.map(AuthenticatedIdentity::name).orElse("anonymous")),
                RootsCache.disabled(), identity
        );
    }
}
