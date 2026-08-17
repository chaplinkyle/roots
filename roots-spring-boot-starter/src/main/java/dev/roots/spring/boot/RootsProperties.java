package dev.roots.spring.boot;

import dev.roots.RootsConfig;
import dev.roots.ProxyPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;
import java.util.List;

/**
 * Type-safe Spring Boot configuration for the embedded Roots server.
 *
 * @param enabled whether the Roots lifecycle is enabled
 * @param transport HTTP transport managed by Spring Boot
 * @param pagesPackage explicit page package, or unset for the conventional package
 * @param apiPackage explicit API package, or unset for the conventional package
 * @param host listener interface address
 * @param port listener port, or zero for an ephemeral port
 * @param development whether development behavior is enabled
 * @param viewTimeout maximum quiet time for a live browser view
 * @param sessionTimeout maximum quiet time for a session
 * @param shutdownTimeout maximum graceful shutdown wait
 * @param maxRequestBytes maximum accepted request-body size
 * @param requestBodyMemoryThreshold bytes retained before request bodies spill to disk
 * @param maxMultipartTextFieldBytes maximum bytes in one non-file multipart field
 * @param requestBodyTemporaryDirectory temp directory, or blank for the JVM default
 * @param maxConcurrentRequests maximum concurrently executing requests
 * @param maxLiveViews maximum retained live browser views
 * @param maxSessions maximum retained sessions
 * @param secureCookies whether session cookies require HTTPS
 * @param contentSecurityPolicy policy sent with HTML responses
 * @param servletPath Servlet URL prefix when {@code transport=servlet}
 * @param trustedProxies proxy CIDRs allowed to supply forwarding headers
 * @param rateLimitRequests requests allowed per client window, or zero to disable
 * @param rateLimitWindow fixed per-client rate-limit window
 * @param maxRateLimitClients maximum retained node-local rate-limit keys
 */
@ConfigurationProperties("roots")
public record RootsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("jdk") RootsTransport transport,
        String pagesPackage,
        String apiPackage,
        @DefaultValue("127.0.0.1") String host,
        @DefaultValue("8081") int port,
        @DefaultValue("true") boolean development,
        @DefaultValue("30m") Duration viewTimeout,
        @DefaultValue("8h") Duration sessionTimeout,
        @DefaultValue("30s") Duration shutdownTimeout,
        @DefaultValue("1048576") int maxRequestBytes,
        @DefaultValue("65536") int requestBodyMemoryThreshold,
        @DefaultValue("1048576") int maxMultipartTextFieldBytes,
        @DefaultValue("") String requestBodyTemporaryDirectory,
        @DefaultValue("20000") int maxConcurrentRequests,
        @DefaultValue("10000") int maxLiveViews,
        @DefaultValue("100000") int maxSessions,
        @DefaultValue("false") boolean secureCookies,
        @DefaultValue(RootsConfig.DEFAULT_CONTENT_SECURITY_POLICY) String contentSecurityPolicy,
        @DefaultValue("/") String servletPath,
        List<String> trustedProxies,
        @DefaultValue("0") int rateLimitRequests,
        @DefaultValue("1m") Duration rateLimitWindow,
        @DefaultValue("100000") int maxRateLimitClients
) {
    /** Validates Servlet mapping syntax independently of the selected transport. */
    public RootsProperties {
        requestBodyTemporaryDirectory = Objects.requireNonNull(requestBodyTemporaryDirectory,
                "requestBodyTemporaryDirectory");
        servletPath = Objects.requireNonNull(servletPath, "servletPath");
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        if (!servletPath.equals("/") && (!servletPath.matches("/[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*")
                || java.util.Arrays.stream(servletPath.substring(1).split("/"))
                .anyMatch(segment -> segment.equals(".") || segment.equals("..")))) {
            throw new IllegalArgumentException(
                    "roots.servlet-path must be / or a normalized absolute path without wildcards"
            );
        }
        ProxyPolicy.trusted(trustedProxies);
        if (rateLimitRequests < 0) {
            throw new IllegalArgumentException("roots.rate-limit-requests must not be negative");
        }
        Objects.requireNonNull(rateLimitWindow, "rateLimitWindow");
        if (rateLimitWindow.isZero() || rateLimitWindow.isNegative()) {
            throw new IllegalArgumentException("roots.rate-limit-window must be positive");
        }
        if (maxRateLimitClients < 1) {
            throw new IllegalArgumentException("roots.max-rate-limit-clients must be positive");
        }
    }
}
