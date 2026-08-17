package dev.roots.spring.boot;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RootsSpringSecurityAutoConfigurationTest {
    private final dev.roots.servlet.ServletIdentityResolver resolver =
            new RootsSpringSecurityAutoConfiguration().rootsSpringSecurityIdentityResolver();

    @Test
    void mapsAuthenticatedSpringPrincipalsAndAuthorities() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "ada",
                "never-propagated",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("accounts:read"))
        );

        var identity = resolver.resolve(request(authentication)).orElseThrow();

        assertEquals("ada", identity.name());
        assertEquals(java.util.Set.of("ROLE_ADMIN", "accounts:read"), identity.authorities());
    }

    @Test
    void rejectsAnonymousAndUnauthenticatedSpringTokens() {
        var anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        );
        var unauthenticated = UsernamePasswordAuthenticationToken.unauthenticated("ada", "password");

        assertTrue(resolver.resolve(request(anonymous)).isEmpty());
        assertTrue(resolver.resolve(request(unauthenticated)).isEmpty());
    }

    @Test
    void fallsBackToAStandardContainerPrincipal() {
        Principal principal = () -> "container-user";

        var identity = resolver.resolve(request(principal)).orElseThrow();

        assertEquals("container-user", identity.name());
        assertTrue(identity.authorities().isEmpty());
        assertTrue(resolver.resolve(request(null)).isEmpty());
    }

    private static HttpServletRequest request(Principal principal) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                RootsSpringSecurityAutoConfigurationTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUserPrincipal" -> principal;
                    case "toString" -> "SecurityRequest[" + principal + "]";
                    default -> defaultValue(method.getReturnType());
                }
        );
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
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }
}
