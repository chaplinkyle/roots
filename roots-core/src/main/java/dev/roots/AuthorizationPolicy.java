package dev.roots;

import java.util.Optional;
import java.util.Objects;

/**
 * Evaluates one named authorization rule.
 *
 * <p>An empty result allows execution. A response rejects it. Policies run for
 * initial page/API requests and again for every live action, and may inspect the
 * current session, logical route, route parameters, headers, and form values.</p>
 */
@FunctionalInterface
public interface AuthorizationPolicy {
    /**
     * Evaluates the current logical application request.
     *
     * @param request immutable request and session context
     * @return empty to allow execution, or the response that rejects it
     * @throws Exception when the policy cannot complete normally
     */
    Optional<Response> authorize(Request request) throws Exception;

    /** Creates an allow result for use from a policy lambda.
     * @return an allow result */
    static Optional<Response> allow() {
        return Optional.empty();
    }

    /**
     * Creates a rejection result for use from a policy lambda.
     *
     * @param response rejection response
     * @return a deny result for use from a policy lambda
     */
    static Optional<Response> deny(Response response) {
        return Optional.of(Objects.requireNonNull(response, "response"));
    }

    /** Requires any authenticated identity.
     * @param rejection response returned to anonymous requests
     * @return authentication policy */
    static AuthorizationPolicy authenticated(Response rejection) {
        Objects.requireNonNull(rejection, "rejection");
        return request -> request.identity().isPresent() ? allow() : deny(rejection);
    }

    /** Requires an exact authority.
     * @param authority required authority
     * @param rejection response returned when the authority is absent
     * @return authority policy */
    static AuthorizationPolicy authority(String authority, Response rejection) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(rejection, "rejection");
        return request -> request.identity()
                .filter(identity -> identity.hasAuthority(authority))
                .isPresent() ? allow() : deny(rejection);
    }

    /** Requires a role using the conventional {@code ROLE_} authority prefix.
     * @param role required role
     * @param rejection response returned when the role is absent
     * @return role policy */
    static AuthorizationPolicy role(String role, Response rejection) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(rejection, "rejection");
        return request -> request.identity()
                .filter(identity -> identity.hasRole(role))
                .isPresent() ? allow() : deny(rejection);
    }
}
