package dev.roots.servlet;

import dev.roots.AuthenticatedIdentity;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;
import java.util.Optional;

/** Resolves transport-neutral Roots identity data from a Servlet request. */
@FunctionalInterface
public interface ServletIdentityResolver {
    /** Resolves the identity before Roots dispatches or starts asynchronous work.
     * @param request current Servlet request
     * @return authenticated identity, or empty for anonymous
     */
    Optional<AuthenticatedIdentity> resolve(HttpServletRequest request);

    /** Uses the Servlet container principal name without assuming a security framework.
     * @return container-principal resolver
     */
    static ServletIdentityResolver containerPrincipal() {
        return request -> Optional.ofNullable(Objects.requireNonNull(request, "request").getUserPrincipal())
                .map(principal -> AuthenticatedIdentity.named(principal.getName()));
    }
}
