package com.chaplin.roots;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthenticationProviderTest {
    @Test
    void preservesTransportIdentityAndCopiesResolvedIdentity() throws Exception {
        var transport = AuthenticatedIdentity.of("container-user", List.of("ROLE_USER"));
        var request = request(Map.of(), Optional.of(transport));

        assertEquals(Optional.of(transport), AuthenticationProvider.transportIdentity().authenticate(request));
        assertSame(request, request.withIdentity(Optional.of(transport)));

        var replacement = AuthenticatedIdentity.of("api-user", List.of("reports:read"));
        var authenticated = request.withIdentity(Optional.of(replacement));
        assertNotSame(request, authenticated);
        assertEquals(Optional.of(replacement), authenticated.identity());
        assertEquals(request.session(), authenticated.session());
        assertEquals(request.headers(), authenticated.headers());
        assertThrows(NullPointerException.class, () -> request.withIdentity(null));
    }

    @Test
    void authenticatesOneStrictBearerCredential() throws Exception {
        var seen = new ArrayList<String>();
        var identity = AuthenticatedIdentity.of("bearer-user", List.of("documents:read"));
        var provider = AuthenticationProvider.bearer(token -> {
            seen.add(token);
            return Optional.of(identity);
        });

        assertEquals(Optional.of(identity), provider.authenticate(request(
                Map.of("AUTHORIZATION", List.of("bEaReR abc.DEF_~+/==")), Optional.empty())));
        assertEquals(List.of("abc.DEF_~+/=="), seen);

        seen.clear();
        var maximumToken = "a".repeat(8_192);
        assertEquals(Optional.of(identity), provider.authenticate(request(
                Map.of("Authorization", List.of("Bearer " + maximumToken)), Optional.empty())));
        assertEquals(List.of(maximumToken), seen);
    }

    @Test
    void rejectsAmbiguousUnsupportedAndMalformedBearerCredentialsBeforeLookup() throws Exception {
        var calls = new ArrayList<String>();
        var provider = AuthenticationProvider.bearer(token -> {
            calls.add(token);
            return Optional.of(AuthenticatedIdentity.named("unexpected"));
        });

        var invalidHeaders = List.of(
                Map.<String, List<String>>of(),
                Map.of("Authorization", List.of("Basic abc")),
                Map.of("Authorization", List.of("Bearer")),
                Map.of("Authorization", List.of("Bearer ")),
                Map.of("Authorization", List.of("Bearer\tabc")),
                Map.of("Authorization", List.of("Bearer abc def")),
                Map.of("Authorization", List.of("Bearer ab=c")),
                Map.of("Authorization", List.of("Bearer abc!")),
                Map.of("Authorization", List.of("Bearer " + "a".repeat(8_193))),
                Map.of("Authorization", List.of("Bearer one", "Bearer two")),
                Map.of("Authorization", List.of("Bearer one"), "authorization", List.of("Bearer two"))
        );
        for (var headers : invalidHeaders) {
            assertTrue(provider.authenticate(request(headers, Optional.empty())).isEmpty(), headers.toString());
        }
        assertTrue(calls.isEmpty());
    }

    @Test
    void validatesProviderAndAuthenticatorResults() {
        assertThrows(NullPointerException.class, () -> AuthenticationProvider.bearer(null));
        var nullResult = AuthenticationProvider.bearer(token -> null);
        assertThrows(NullPointerException.class, () -> nullResult.authenticate(request(
                Map.of("Authorization", List.of("Bearer valid")), Optional.empty())));
    }

    private static Request request(
            Map<String, List<String>> headers,
            Optional<AuthenticatedIdentity> identity
    ) {
        return new Request(
                "GET",
                "/reports",
                "/reports",
                Map.of(),
                Map.of(),
                headers,
                Map.of(),
                Map.of(),
                new byte[0],
                new Session("authentication-provider-test"),
                RootsCache.disabled(),
                identity,
                TraceContext.create(),
                "",
                ClientConnection.unknown("http", "localhost")
        );
    }
}
