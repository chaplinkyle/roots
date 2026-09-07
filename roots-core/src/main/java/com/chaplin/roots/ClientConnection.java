package com.chaplin.roots;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable network and public-origin details resolved for one HTTP request.
 *
 * <p>The client address is absent only when a custom transport cannot expose its
 * peer. Forwarded values are used only when the configured {@link ProxyPolicy}
 * trusts the directly connected peer.</p>
 *
 * @param clientAddress numeric client address, or empty when unavailable
 * @param scheme external {@code http} or {@code https} scheme
 * @param authority external host and optional port
 * @param forwarded whether trusted proxy metadata changed the direct connection
 */
public record ClientConnection(
        Optional<InetAddress> clientAddress,
        String scheme,
        String authority,
        boolean forwarded
) {
    /** Validates and normalizes connection details. */
    public ClientConnection {
        clientAddress = Objects.requireNonNull(clientAddress, "clientAddress");
        scheme = Objects.requireNonNull(scheme, "scheme").toLowerCase(Locale.ROOT);
        authority = Objects.requireNonNull(authority, "authority");
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Connection scheme must be http or https");
        }
        if (authority.isBlank() || authority.length() > 512
                || authority.chars().anyMatch(character -> Character.isISOControl(character)
                || Character.isWhitespace(character))
                || authority.indexOf('/') >= 0 || authority.indexOf('\\') >= 0
                || authority.indexOf('?') >= 0 || authority.indexOf('#') >= 0
                || authority.indexOf('@') >= 0) {
            throw new IllegalArgumentException("Connection authority is invalid");
        }
        var parsedOrigin = parseOrigin(scheme, authority);
        if (parsedOrigin.getHost() == null || !parsedOrigin.getPath().isEmpty()
                || parsedOrigin.getRawUserInfo() != null || parsedOrigin.getRawQuery() != null
                || parsedOrigin.getRawFragment() != null) {
            throw new IllegalArgumentException("Connection authority is invalid");
        }
    }

    /** Creates direct connection details for a known peer.
     * @param address numeric peer address
     * @param scheme request scheme
     * @param authority request authority
     * @return direct connection details
     */
    public static ClientConnection direct(InetAddress address, String scheme, String authority) {
        return new ClientConnection(Optional.of(Objects.requireNonNull(address, "address")), scheme, authority, false);
    }

    /** Creates direct connection details when the transport cannot expose a peer.
     * @param scheme request scheme
     * @param authority request authority
     * @return connection details without a client address
     */
    public static ClientConnection unknown(String scheme, String authority) {
        return new ClientConnection(Optional.empty(), scheme, authority, false);
    }

    /** Returns the numeric client address without reverse DNS.
     * @return address text, or {@code unknown}
     */
    public String clientAddressText() {
        return clientAddress.map(InetAddress::getHostAddress).orElse("unknown");
    }

    /** Returns the validated public origin.
     * @return origin URI
     */
    public URI origin() {
        return URI.create(scheme + "://" + authority);
    }

    /** Returns a copy resolved from trusted proxy metadata.
     * @param address resolved numeric client address
     * @param scheme resolved external scheme
     * @param authority resolved external authority
     * @return forwarded connection details
     */
    public ClientConnection forwarded(InetAddress address, String scheme, String authority) {
        return new ClientConnection(Optional.of(Objects.requireNonNull(address, "address")), scheme, authority, true);
    }

    private static URI parseOrigin(String scheme, String authority) {
        try {
            return URI.create(scheme + "://" + authority);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Connection authority is invalid", exception);
        }
    }
}
