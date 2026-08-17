package dev.roots;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable authenticated principal name and exact granted authorities.
 *
 * @param name authenticated principal name
 * @param authorities exact granted authority strings
 */
public record AuthenticatedIdentity(String name, Set<String> authorities) {
    private static final int MAX_NAME_LENGTH = 256;
    private static final int MAX_AUTHORITIES = 256;
    private static final int MAX_AUTHORITY_LENGTH = 256;

    /** Validates and copies identity data. */
    public AuthenticatedIdentity {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH || containsControl(name)) {
            throw new IllegalArgumentException(
                    "Authenticated identity name must be non-blank, control-free, and at most 256 characters"
            );
        }
        authorities = Set.copyOf(Objects.requireNonNull(authorities, "authorities"));
        if (authorities.size() > MAX_AUTHORITIES) {
            throw new IllegalArgumentException("Authenticated identities may contain at most 256 authorities");
        }
        if (authorities.stream().anyMatch(authority -> authority.isBlank()
                || authority.length() > MAX_AUTHORITY_LENGTH || containsControl(authority))) {
            throw new IllegalArgumentException(
                    "Authenticated authorities must be non-blank, control-free, and at most 256 characters"
            );
        }
    }

    /** Creates an identity without enumerated authorities.
     * @param name authenticated principal name
     * @return authenticated identity */
    public static AuthenticatedIdentity named(String name) {
        return new AuthenticatedIdentity(name, Set.of());
    }

    /** Creates an identity with exact authorities.
     * @param name authenticated principal name
     * @param authorities granted authorities
     * @return authenticated identity */
    public static AuthenticatedIdentity of(String name, Collection<String> authorities) {
        Objects.requireNonNull(authorities, "authorities");
        if (authorities.size() > MAX_AUTHORITIES) {
            throw new IllegalArgumentException("Authenticated identities may contain at most 256 authorities");
        }
        return new AuthenticatedIdentity(name, Set.copyOf(authorities));
    }

    /** Tests an exact authority.
     * @param authority authority name
     * @return whether it is granted */
    public boolean hasAuthority(String authority) {
        return authorities.contains(Objects.requireNonNull(authority, "authority"));
    }

    /** Tests a role using the conventional {@code ROLE_} authority prefix.
     * @param role role name with or without the prefix
     * @return whether the role is granted */
    public boolean hasRole(String role) {
        Objects.requireNonNull(role, "role");
        return hasAuthority(role.startsWith("ROLE_") ? role : "ROLE_" + role);
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
