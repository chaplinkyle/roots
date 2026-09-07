package com.chaplin.roots;

import com.chaplin.roots.internal.TrustedProxyPolicy;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves client and external-origin details at the trusted transport boundary.
 *
 * <p>The default policy ignores every forwarding header. Applications should
 * trust only explicitly configured proxy address ranges.</p>
 */
@FunctionalInterface
public interface ProxyPolicy {
    /** Resolves connection details from a direct peer and immutable request headers.
     * @param direct transport peer details
     * @param headers repeated request headers
     * @return resolved connection details
     */
    ClientConnection resolve(ClientConnection direct, Map<String, List<String>> headers);

    /** Returns the safe policy that ignores all forwarding headers.
     * @return direct-only policy
     */
    static ProxyPolicy directOnly() {
        return (direct, headers) -> {
            Objects.requireNonNull(headers, "headers");
            return Objects.requireNonNull(direct, "direct");
        };
    }

    /** Creates a policy that accepts forwarding metadata only from listed CIDR ranges.
     * Both IPv4 and IPv6 prefixes are supported. An empty list is equivalent to
     * {@link #directOnly()}.
     * @param trustedCidrs trusted proxy address ranges
     * @return validated trusted-proxy policy
     */
    static ProxyPolicy trusted(List<String> trustedCidrs) {
        return TrustedProxyPolicy.create(trustedCidrs);
    }

    /** Creates a trusted-proxy policy from CIDR ranges.
     * @param trustedCidrs trusted proxy address ranges
     * @return validated trusted-proxy policy
     */
    static ProxyPolicy trusted(String... trustedCidrs) {
        return trusted(List.of(trustedCidrs));
    }
}
