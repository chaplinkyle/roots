package dev.roots;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the authenticated identity for every session-bearing Roots request.
 *
 * <p>Providers receive the immutable request after bounded body parsing and session
 * resolution but before middleware, routing, or authorization. They run again for
 * live actions, streams, and development inspection requests. Returning an empty
 * result keeps the request anonymous.</p>
 */
@FunctionalInterface
public interface AuthenticationProvider {
    /**
     * Authenticates one request.
     *
     * @param request request carrying transport identity, headers, cookies, and session
     * @return authenticated identity, or empty for anonymous
     * @throws Exception when the backing identity service cannot complete normally
     */
    Optional<AuthenticatedIdentity> authenticate(Request request) throws Exception;

    /**
     * Preserves the identity supplied by a transport adapter.
     *
     * <p>This is the default and allows Servlet containers and Spring Security to
     * remain the authentication authority.</p>
     *
     * @return transport-identity provider
     */
    static AuthenticationProvider transportIdentity() {
        return Request::identity;
    }

    /**
     * Creates a strict RFC 6750 Bearer-token provider.
     *
     * <p>The authenticator is invoked only for one unambiguous Authorization header
     * containing a syntactically valid token of at most 8192 characters. Missing,
     * duplicate, unsupported, or malformed credentials remain anonymous.</p>
     *
     * @param authenticator application-owned token verification and identity lookup
     * @return Bearer-token provider
     */
    static AuthenticationProvider bearer(BearerTokenAuthenticator authenticator) {
        Objects.requireNonNull(authenticator, "authenticator");
        return request -> {
            Objects.requireNonNull(request, "request");
            var values = authorizationValues(request);
            if (values.size() != 1) {
                return Optional.empty();
            }
            var token = bearerToken(values.getFirst());
            if (token == null) {
                return Optional.empty();
            }
            return Objects.requireNonNull(authenticator.authenticate(token), "Bearer authenticator result");
        };
    }

    private static List<String> authorizationValues(Request request) {
        var values = new ArrayList<String>();
        request.headers().forEach((name, entries) -> {
            if (name.toLowerCase(Locale.ROOT).equals("authorization")) {
                values.addAll(entries);
            }
        });
        return values;
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || authorization.length() > 8_199) {
            return null;
        }
        var separator = authorization.indexOf(' ');
        if (separator < 0 || !authorization.substring(0, separator).equalsIgnoreCase("Bearer")) {
            return null;
        }
        var start = separator;
        while (start < authorization.length() && authorization.charAt(start) == ' ') {
            start++;
        }
        if (start == authorization.length()) {
            return null;
        }
        var token = authorization.substring(start);
        if (token.length() > 8_192) {
            return null;
        }
        var padding = false;
        for (var index = 0; index < token.length(); index++) {
            var character = token.charAt(index);
            if (character == '=') {
                padding = true;
            } else if (padding || !isBearerCharacter(character)) {
                return null;
            }
        }
        return token;
    }

    private static boolean isBearerCharacter(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || character == '-' || character == '.' || character == '_'
                || character == '~' || character == '+' || character == '/';
    }

    /** Application-owned verification for one syntactically valid Bearer token. */
    @FunctionalInterface
    interface BearerTokenAuthenticator {
        /**
         * Verifies a credential and resolves its immutable identity.
         *
         * @param token exact Bearer token without scheme or whitespace
         * @return authenticated identity, or empty when the credential is not accepted
         * @throws Exception when verification cannot complete normally
         */
        Optional<AuthenticatedIdentity> authenticate(String token) throws Exception;
    }
}
