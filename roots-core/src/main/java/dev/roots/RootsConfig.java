package dev.roots;

import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Immutable startup configuration for a Roots application.
 *
 * @param applicationClass application class used as the convention-scan anchor
 * @param pagesPackage package containing convention pages and layouts
 * @param apiPackage package containing convention API routes
 * @param host interface address for the built-in server
 * @param port listening port, or zero for an ephemeral port
 * @param development whether development-mode asset and error behavior is enabled
 * @param viewTimeout maximum quiet time before a live view expires
 * @param sessionTimeout maximum quiet time before a session expires
 * @param maxRequestBytes maximum accepted request-body size
 * @param maxConcurrentRequests maximum requests executing concurrently
 * @param maxLiveViews maximum live views retained by this JVM
 * @param maxSessions maximum sessions retained by the configured repository
 * @param secureCookies whether session cookies include the {@code Secure} attribute
 * @param instanceFactory constructor integration for discovered application types
 * @param authorizationPolicies immutable named authorization-policy registry
 * @param middleware immutable request middleware chain
 * @param exceptionMappers immutable typed exception-mapper chain
 * @param cache application-scoped cache implementation
 * @param contentSecurityPolicy policy enforced on every HTML response
 * @param sessionRepository session identity, expiry, capacity, and value repository
 * @param requestObservers immutable request-completion observers
 * @param proxyPolicy trusted reverse-proxy resolution policy
 * @param rateLimiter pre-session request admission limiter
 * @param requestBodyPolicy heap and temporary-storage request-body policy
 * @param liveViewOwnership local or distributed live-view lease registry
 * @param authenticationProvider request identity provider
 */
public record RootsConfig(
        Class<?> applicationClass,
        String pagesPackage,
        String apiPackage,
        String host,
        int port,
        boolean development,
        Duration viewTimeout,
        Duration sessionTimeout,
        int maxRequestBytes,
        int maxConcurrentRequests,
        int maxLiveViews,
        int maxSessions,
        boolean secureCookies,
        InstanceFactory instanceFactory,
        Map<String, AuthorizationPolicy> authorizationPolicies,
        List<Middleware> middleware,
        List<ExceptionMapper> exceptionMappers,
        RootsCache cache,
        String contentSecurityPolicy,
        SessionRepository sessionRepository,
        List<RequestObserver> requestObservers,
        ProxyPolicy proxyPolicy,
        RateLimiter rateLimiter,
        RequestBodyPolicy requestBodyPolicy,
        LiveViewOwnership liveViewOwnership,
        AuthenticationProvider authenticationProvider
) {
    /** Default maximum accepted request-body size. */
    public static final int DEFAULT_MAX_REQUEST_BYTES = 1_048_576;
    /** Restrictive policy used unless an application explicitly configures another policy. */
    public static final String DEFAULT_CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                    + "connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; "
                    + "form-action 'self'";

    /** Validates and defensively copies the canonical configuration. */
    public RootsConfig {
        Objects.requireNonNull(applicationClass, "applicationClass");
        Objects.requireNonNull(pagesPackage, "pagesPackage");
        Objects.requireNonNull(apiPackage, "apiPackage");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(viewTimeout, "viewTimeout");
        Objects.requireNonNull(sessionTimeout, "sessionTimeout");
        Objects.requireNonNull(instanceFactory, "instanceFactory");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(sessionRepository, "sessionRepository");
        requestObservers = List.copyOf(Objects.requireNonNull(requestObservers, "requestObservers"));
        proxyPolicy = Objects.requireNonNull(proxyPolicy, "proxyPolicy");
        rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        requestBodyPolicy = Objects.requireNonNull(requestBodyPolicy, "requestBodyPolicy");
        liveViewOwnership = Objects.requireNonNull(liveViewOwnership, "liveViewOwnership");
        authenticationProvider = Objects.requireNonNull(authenticationProvider, "authenticationProvider");
        contentSecurityPolicy = validateContentSecurityPolicy(contentSecurityPolicy);
        var copiedPolicies = new LinkedHashMap<String, AuthorizationPolicy>();
        Objects.requireNonNull(authorizationPolicies, "authorizationPolicies").forEach((name, policy) -> {
            validatePolicyName(name);
            copiedPolicies.put(name, Objects.requireNonNull(policy, "authorization policy"));
        });
        authorizationPolicies = Map.copyOf(copiedPolicies);
        middleware = List.copyOf(middleware);
        exceptionMappers = List.copyOf(exceptionMappers);
        if (pagesPackage.isBlank() || apiPackage.isBlank() || host.isBlank()) {
            throw new IllegalArgumentException("Packages and host must not be blank");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        if (viewTimeout.isZero() || viewTimeout.isNegative()) {
            throw new IllegalArgumentException("View timeout must be positive");
        }
        if (sessionTimeout.isZero() || sessionTimeout.isNegative()) {
            throw new IllegalArgumentException("Session timeout must be positive");
        }
        if (viewTimeout.toMillis() == 0 || sessionTimeout.toMillis() == 0) {
            throw new IllegalArgumentException("Timeouts must be at least one millisecond");
        }
        if (maxRequestBytes < 1 || maxRequestBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Request byte limit must be between 1 and 2147483646");
        }
        if (maxConcurrentRequests < 1 || maxLiveViews < 1 || maxSessions < 1) {
            throw new IllegalArgumentException("Request, live view, and session limits must be positive");
        }
        if (maxConcurrentRequests <= maxLiveViews
                && !(maxConcurrentRequests == Integer.MAX_VALUE && maxLiveViews == Integer.MAX_VALUE)) {
            throw new IllegalArgumentException("Request limit must exceed the live view limit to reserve action capacity");
        }
    }

    /** Preserves the configuration constructor from before Core authentication providers.
     * @param applicationClass convention-scan anchor
     * @param pagesPackage pages package
     * @param apiPackage API package
     * @param host server host
     * @param port server port
     * @param development development mode
     * @param viewTimeout live-view timeout
     * @param sessionTimeout session timeout
     * @param maxRequestBytes request-body limit
     * @param maxConcurrentRequests concurrent-request limit
     * @param maxLiveViews live-view limit
     * @param maxSessions session limit
     * @param secureCookies secure-cookie flag
     * @param instanceFactory application instance factory
     * @param authorizationPolicies named authorization policies
     * @param middleware request middleware
     * @param exceptionMappers exception mappers
     * @param cache application cache
     * @param contentSecurityPolicy HTML response policy
     * @param sessionRepository session repository
     * @param requestObservers request observers
     * @param proxyPolicy proxy policy
     * @param rateLimiter rate limiter
     * @param requestBodyPolicy request-body storage policy
     * @param liveViewOwnership live-view lease registry */
    public RootsConfig(
            Class<?> applicationClass,
            String pagesPackage,
            String apiPackage,
            String host,
            int port,
            boolean development,
            Duration viewTimeout,
            Duration sessionTimeout,
            int maxRequestBytes,
            int maxConcurrentRequests,
            int maxLiveViews,
            int maxSessions,
            boolean secureCookies,
            InstanceFactory instanceFactory,
            Map<String, AuthorizationPolicy> authorizationPolicies,
            List<Middleware> middleware,
            List<ExceptionMapper> exceptionMappers,
            RootsCache cache,
            String contentSecurityPolicy,
            SessionRepository sessionRepository,
            List<RequestObserver> requestObservers,
            ProxyPolicy proxyPolicy,
            RateLimiter rateLimiter,
            RequestBodyPolicy requestBodyPolicy,
            LiveViewOwnership liveViewOwnership
    ) {
        this(applicationClass, pagesPackage, apiPackage, host, port, development, viewTimeout, sessionTimeout,
                maxRequestBytes, maxConcurrentRequests, maxLiveViews, maxSessions, secureCookies, instanceFactory,
                authorizationPolicies, middleware, exceptionMappers, cache, contentSecurityPolicy, sessionRepository,
                requestObservers, proxyPolicy, rateLimiter, requestBodyPolicy, liveViewOwnership,
                AuthenticationProvider.transportIdentity());
    }

    /** Preserves the configuration constructor from before live-view ownership leases.
     * @param applicationClass convention-scan anchor
     * @param pagesPackage pages package
     * @param apiPackage API package
     * @param host server host
     * @param port server port
     * @param development development mode
     * @param viewTimeout live-view timeout
     * @param sessionTimeout session timeout
     * @param maxRequestBytes request-body limit
     * @param maxConcurrentRequests concurrent-request limit
     * @param maxLiveViews live-view limit
     * @param maxSessions session limit
     * @param secureCookies secure-cookie flag
     * @param instanceFactory application instance factory
     * @param authorizationPolicies named authorization policies
     * @param middleware request middleware
     * @param exceptionMappers exception mappers
     * @param cache application cache
     * @param contentSecurityPolicy HTML response policy
     * @param sessionRepository session repository
     * @param requestObservers request observers
     * @param proxyPolicy proxy policy
     * @param rateLimiter rate limiter
     * @param requestBodyPolicy request-body storage policy */
    public RootsConfig(
            Class<?> applicationClass,
            String pagesPackage,
            String apiPackage,
            String host,
            int port,
            boolean development,
            Duration viewTimeout,
            Duration sessionTimeout,
            int maxRequestBytes,
            int maxConcurrentRequests,
            int maxLiveViews,
            int maxSessions,
            boolean secureCookies,
            InstanceFactory instanceFactory,
            Map<String, AuthorizationPolicy> authorizationPolicies,
            List<Middleware> middleware,
            List<ExceptionMapper> exceptionMappers,
            RootsCache cache,
            String contentSecurityPolicy,
            SessionRepository sessionRepository,
            List<RequestObserver> requestObservers,
            ProxyPolicy proxyPolicy,
            RateLimiter rateLimiter,
            RequestBodyPolicy requestBodyPolicy
    ) {
        this(applicationClass, pagesPackage, apiPackage, host, port, development, viewTimeout, sessionTimeout,
                maxRequestBytes, maxConcurrentRequests, maxLiveViews, maxSessions, secureCookies, instanceFactory,
                authorizationPolicies, middleware, exceptionMappers, cache, contentSecurityPolicy, sessionRepository,
                requestObservers, proxyPolicy, rateLimiter, requestBodyPolicy, LiveViewOwnership.inMemory(),
                AuthenticationProvider.transportIdentity());
    }

    /** Preserves the configuration constructor from before request-body spooling.
     * @param applicationClass convention-scan anchor
     * @param pagesPackage pages package
     * @param apiPackage API package
     * @param host server host
     * @param port server port
     * @param development development mode
     * @param viewTimeout live-view timeout
     * @param sessionTimeout session timeout
     * @param maxRequestBytes request-body limit
     * @param maxConcurrentRequests concurrent-request limit
     * @param maxLiveViews live-view limit
     * @param maxSessions session limit
     * @param secureCookies secure-cookie flag
     * @param instanceFactory application instance factory
     * @param authorizationPolicies named authorization policies
     * @param middleware request middleware
     * @param exceptionMappers exception mappers
     * @param cache application cache
     * @param contentSecurityPolicy HTML response policy
     * @param sessionRepository session repository
     * @param requestObservers request observers
     * @param proxyPolicy proxy policy
     * @param rateLimiter rate limiter */
    public RootsConfig(
            Class<?> applicationClass,
            String pagesPackage,
            String apiPackage,
            String host,
            int port,
            boolean development,
            Duration viewTimeout,
            Duration sessionTimeout,
            int maxRequestBytes,
            int maxConcurrentRequests,
            int maxLiveViews,
            int maxSessions,
            boolean secureCookies,
            InstanceFactory instanceFactory,
            Map<String, AuthorizationPolicy> authorizationPolicies,
            List<Middleware> middleware,
            List<ExceptionMapper> exceptionMappers,
            RootsCache cache,
            String contentSecurityPolicy,
            SessionRepository sessionRepository,
            List<RequestObserver> requestObservers,
            ProxyPolicy proxyPolicy,
            RateLimiter rateLimiter
    ) {
        this(applicationClass, pagesPackage, apiPackage, host, port, development, viewTimeout, sessionTimeout,
                maxRequestBytes, maxConcurrentRequests, maxLiveViews, maxSessions, secureCookies, instanceFactory,
                authorizationPolicies, middleware, exceptionMappers, cache, contentSecurityPolicy, sessionRepository,
                requestObservers, proxyPolicy, rateLimiter, RequestBodyPolicy.defaults());
    }

    /** Preserves the configuration constructor from before proxy and rate-limit policies.
     * @param applicationClass convention-scan anchor
     * @param pagesPackage pages package
     * @param apiPackage API package
     * @param host server host
     * @param port server port
     * @param development development mode
     * @param viewTimeout live-view timeout
     * @param sessionTimeout session timeout
     * @param maxRequestBytes request-body limit
     * @param maxConcurrentRequests concurrent-request limit
     * @param maxLiveViews live-view limit
     * @param maxSessions session limit
     * @param secureCookies secure-cookie flag
     * @param instanceFactory application instance factory
     * @param authorizationPolicies named authorization policies
     * @param middleware request middleware
     * @param exceptionMappers exception mappers
     * @param cache application cache
     * @param contentSecurityPolicy HTML response policy
     * @param sessionRepository session repository
     * @param requestObservers request observers
     */
    public RootsConfig(
            Class<?> applicationClass,
            String pagesPackage,
            String apiPackage,
            String host,
            int port,
            boolean development,
            Duration viewTimeout,
            Duration sessionTimeout,
            int maxRequestBytes,
            int maxConcurrentRequests,
            int maxLiveViews,
            int maxSessions,
            boolean secureCookies,
            InstanceFactory instanceFactory,
            Map<String, AuthorizationPolicy> authorizationPolicies,
            List<Middleware> middleware,
            List<ExceptionMapper> exceptionMappers,
            RootsCache cache,
            String contentSecurityPolicy,
            SessionRepository sessionRepository,
            List<RequestObserver> requestObservers
    ) {
        this(applicationClass, pagesPackage, apiPackage, host, port, development, viewTimeout, sessionTimeout,
                maxRequestBytes, maxConcurrentRequests, maxLiveViews, maxSessions, secureCookies, instanceFactory,
                authorizationPolicies, middleware, exceptionMappers, cache, contentSecurityPolicy, sessionRepository,
                requestObservers, ProxyPolicy.directOnly(), RateLimiter.unlimited());
    }

    /** Preserves the configuration constructor from before configurable CSP.
     * @param applicationClass convention-scan anchor
     * @param pagesPackage pages package
     * @param apiPackage API package
     * @param host server host
     * @param port server port
     * @param development development mode
     * @param viewTimeout live-view timeout
     * @param sessionTimeout session timeout
     * @param maxRequestBytes request-body limit
     * @param maxConcurrentRequests concurrent-request limit
     * @param maxLiveViews live-view limit
     * @param maxSessions session limit
     * @param secureCookies secure-cookie flag
     * @param instanceFactory application instance factory
     * @param authorizationPolicies named authorization policies
     * @param middleware request middleware
     * @param exceptionMappers exception mappers
     * @param cache application cache */
    public RootsConfig(
            Class<?> applicationClass,
            String pagesPackage,
            String apiPackage,
            String host,
            int port,
            boolean development,
            Duration viewTimeout,
            Duration sessionTimeout,
            int maxRequestBytes,
            int maxConcurrentRequests,
            int maxLiveViews,
            int maxSessions,
            boolean secureCookies,
            InstanceFactory instanceFactory,
            Map<String, AuthorizationPolicy> authorizationPolicies,
            List<Middleware> middleware,
            List<ExceptionMapper> exceptionMappers,
            RootsCache cache
    ) {
        this(
                applicationClass,
                pagesPackage,
                apiPackage,
                host,
                port,
                development,
                viewTimeout,
                sessionTimeout,
                maxRequestBytes,
                maxConcurrentRequests,
                maxLiveViews,
                maxSessions,
                secureCookies,
                instanceFactory,
                authorizationPolicies,
                middleware,
                exceptionMappers,
                cache,
                DEFAULT_CONTENT_SECURITY_POLICY,
                SessionRepository.inMemory(),
                List.of()
        );
    }

    /** Preserves the configuration constructor from before application-scoped caching.
     * @param applicationClass convention-scan anchor
     * @param pagesPackage pages package
     * @param apiPackage API package
     * @param host server host
     * @param port server port
     * @param development development mode
     * @param viewTimeout live-view timeout
     * @param sessionTimeout session timeout
     * @param maxRequestBytes request-body limit
     * @param maxConcurrentRequests concurrent-request limit
     * @param maxLiveViews live-view limit
     * @param maxSessions session limit
     * @param secureCookies secure-cookie flag
     * @param instanceFactory application instance factory
     * @param authorizationPolicies named authorization policies
     * @param middleware request middleware
     * @param exceptionMappers exception mappers */
    public RootsConfig(
            Class<?> applicationClass,
            String pagesPackage,
            String apiPackage,
            String host,
            int port,
            boolean development,
            Duration viewTimeout,
            Duration sessionTimeout,
            int maxRequestBytes,
            int maxConcurrentRequests,
            int maxLiveViews,
            int maxSessions,
            boolean secureCookies,
            InstanceFactory instanceFactory,
            Map<String, AuthorizationPolicy> authorizationPolicies,
            List<Middleware> middleware,
            List<ExceptionMapper> exceptionMappers
    ) {
        this(
                applicationClass,
                pagesPackage,
                apiPackage,
                host,
                port,
                development,
                viewTimeout,
                sessionTimeout,
                maxRequestBytes,
                maxConcurrentRequests,
                maxLiveViews,
                maxSessions,
                secureCookies,
                instanceFactory,
                authorizationPolicies,
                middleware,
                exceptionMappers,
                RootsCache.inMemory()
        );
    }

    /** Preserves the configuration constructor from before configurable request limits.
     * @param applicationClass convention-scan anchor
     * @param pagesPackage pages package
     * @param apiPackage API package
     * @param host server host
     * @param port server port
     * @param development development mode
     * @param viewTimeout live-view timeout
     * @param sessionTimeout session timeout
     * @param maxConcurrentRequests concurrent-request limit
     * @param maxLiveViews live-view limit
     * @param maxSessions session limit
     * @param secureCookies secure-cookie flag
     * @param instanceFactory application instance factory
     * @param authorizationPolicies named authorization policies
     * @param middleware request middleware
     * @param exceptionMappers exception mappers */
    public RootsConfig(
            Class<?> applicationClass,
            String pagesPackage,
            String apiPackage,
            String host,
            int port,
            boolean development,
            Duration viewTimeout,
            Duration sessionTimeout,
            int maxConcurrentRequests,
            int maxLiveViews,
            int maxSessions,
            boolean secureCookies,
            InstanceFactory instanceFactory,
            Map<String, AuthorizationPolicy> authorizationPolicies,
            List<Middleware> middleware,
            List<ExceptionMapper> exceptionMappers
    ) {
        this(
                applicationClass,
                pagesPackage,
                apiPackage,
                host,
                port,
                development,
                viewTimeout,
                sessionTimeout,
                DEFAULT_MAX_REQUEST_BYTES,
                maxConcurrentRequests,
                maxLiveViews,
                maxSessions,
                secureCookies,
                instanceFactory,
                authorizationPolicies,
                middleware,
                exceptionMappers
        );
    }

    /** Preserves the configuration constructor from before declarative authorization.
     * @param applicationClass convention-scan anchor
     * @param pagesPackage pages package
     * @param apiPackage API package
     * @param host server host
     * @param port server port
     * @param development development mode
     * @param viewTimeout live-view timeout
     * @param sessionTimeout session timeout
     * @param maxConcurrentRequests concurrent-request limit
     * @param maxLiveViews live-view limit
     * @param maxSessions session limit
     * @param secureCookies secure-cookie flag
     * @param instanceFactory application instance factory
     * @param middleware request middleware
     * @param exceptionMappers exception mappers */
    public RootsConfig(
            Class<?> applicationClass,
            String pagesPackage,
            String apiPackage,
            String host,
            int port,
            boolean development,
            Duration viewTimeout,
            Duration sessionTimeout,
            int maxConcurrentRequests,
            int maxLiveViews,
            int maxSessions,
            boolean secureCookies,
            InstanceFactory instanceFactory,
            List<Middleware> middleware,
            List<ExceptionMapper> exceptionMappers
    ) {
        this(
                applicationClass,
                pagesPackage,
                apiPackage,
                host,
                port,
                development,
                viewTimeout,
                sessionTimeout,
                DEFAULT_MAX_REQUEST_BYTES,
                maxConcurrentRequests,
                maxLiveViews,
                maxSessions,
                secureCookies,
                instanceFactory,
                Map.of(),
                middleware,
                exceptionMappers
        );
    }

    /** Preserves the pre-admission-control constructor for source compatibility.
     * @param applicationClass convention-scan anchor
     * @param pagesPackage pages package
     * @param apiPackage API package
     * @param host server host
     * @param port server port
     * @param development development mode
     * @param viewTimeout live-view timeout
     * @param sessionTimeout session timeout
     * @param maxLiveViews live-view limit
     * @param maxSessions session limit
     * @param secureCookies secure-cookie flag
     * @param instanceFactory application instance factory
     * @param middleware request middleware
     * @param exceptionMappers exception mappers */
    public RootsConfig(
            Class<?> applicationClass,
            String pagesPackage,
            String apiPackage,
            String host,
            int port,
            boolean development,
            Duration viewTimeout,
            Duration sessionTimeout,
            int maxLiveViews,
            int maxSessions,
            boolean secureCookies,
            InstanceFactory instanceFactory,
            List<Middleware> middleware,
            List<ExceptionMapper> exceptionMappers
    ) {
        this(
                applicationClass,
                pagesPackage,
                apiPackage,
                host,
                port,
                development,
                viewTimeout,
                sessionTimeout,
                DEFAULT_MAX_REQUEST_BYTES,
                defaultRequestLimit(maxLiveViews),
                maxLiveViews,
                maxSessions,
                secureCookies,
                instanceFactory,
                Map.of(),
                middleware,
                exceptionMappers
        );
    }

    /** Preserves the original 0.1 constructor for source compatibility.
     * @param applicationClass convention-scan anchor
     * @param pagesPackage pages package
     * @param apiPackage API package
     * @param host server host
     * @param port server port
     * @param development development mode
     * @param viewTimeout live-view timeout */
    public RootsConfig(
            Class<?> applicationClass,
            String pagesPackage,
            String apiPackage,
            String host,
            int port,
            boolean development,
            Duration viewTimeout
    ) {
        this(
                applicationClass,
                pagesPackage,
                apiPackage,
                host,
                port,
                development,
                viewTimeout,
                Duration.ofHours(8),
                DEFAULT_MAX_REQUEST_BYTES,
                20_000,
                10_000,
                100_000,
                false,
                InstanceFactory.reflection(),
                Map.of(),
                List.of(),
                List.of(),
                RootsCache.inMemory()
        );
    }

    /** Creates a builder whose convention packages derive from an application class.
     * @param applicationClass convention-scan anchor
     * @return configuration builder */
    public static Builder forApplication(Class<?> applicationClass) {
        return new Builder(applicationClass);
    }

    private static int defaultRequestLimit(int maxLiveViews) {
        return maxLiveViews == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : Math.max(20_000, maxLiveViews + 1);
    }

    private static void validatePolicyName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
            throw new IllegalArgumentException("Invalid authorization policy name: " + name);
        }
    }

    private static String validateContentSecurityPolicy(String policy) {
        Objects.requireNonNull(policy, "contentSecurityPolicy");
        var normalized = policy.strip();
        if (normalized.isEmpty() || normalized.length() > 8_192
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "Content Security Policy must be non-blank, contain no control characters, and be at most 8192 characters"
            );
        }
        return normalized;
    }

    /** Fluent builder for validated application configuration. */
    public static final class Builder {
        private final Class<?> applicationClass;
        private String pagesPackage;
        private String apiPackage;
        private String host = "127.0.0.1";
        private int port = 8080;
        private boolean development = true;
        private Duration viewTimeout = Duration.ofMinutes(30);
        private Duration sessionTimeout = Duration.ofHours(8);
        private int maxRequestBytes = DEFAULT_MAX_REQUEST_BYTES;
        private int maxConcurrentRequests = 20_000;
        private int maxLiveViews = 10_000;
        private int maxSessions = 100_000;
        private boolean secureCookies;
        private String contentSecurityPolicy = DEFAULT_CONTENT_SECURITY_POLICY;
        private InstanceFactory instanceFactory = InstanceFactory.reflection();
        private final Map<String, AuthorizationPolicy> authorizationPolicies = new LinkedHashMap<>();
        private final List<Middleware> middleware = new ArrayList<>();
        private final List<ExceptionMapper> exceptionMappers = new ArrayList<>();
        private RootsCache cache = RootsCache.inMemory();
        private SessionRepository sessionRepository = SessionRepository.inMemory();
        private final List<RequestObserver> requestObservers = new ArrayList<>();
        private ProxyPolicy proxyPolicy = ProxyPolicy.directOnly();
        private RateLimiter rateLimiter = RateLimiter.unlimited();
        private RequestBodyPolicy requestBodyPolicy = RequestBodyPolicy.defaults();
        private LiveViewOwnership liveViewOwnership = LiveViewOwnership.inMemory();
        private AuthenticationProvider authenticationProvider = AuthenticationProvider.transportIdentity();

        private Builder(Class<?> applicationClass) {
            this.applicationClass = Objects.requireNonNull(applicationClass);
            var basePackage = applicationClass.getPackageName();
            this.pagesPackage = basePackage + ".pages";
            this.apiPackage = basePackage + ".api";
        }

        /** Sets the convention pages package.
         * @param pagesPackage pages package
         * @return this builder */
        public Builder pagesPackage(String pagesPackage) {
            this.pagesPackage = pagesPackage;
            return this;
        }

        /** Sets the convention API package.
         * @param apiPackage API package
         * @return this builder */
        public Builder apiPackage(String apiPackage) {
            this.apiPackage = apiPackage;
            return this;
        }

        /** Sets the server bind address.
         * @param host host or interface address
         * @return this builder */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /** Sets the listening port.
         * @param port port, or zero for an ephemeral port
         * @return this builder */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** Enables or disables development behavior.
         * @param development whether development mode is enabled
         * @return this builder */
        public Builder development(boolean development) {
            this.development = development;
            return this;
        }

        /** Sets the maximum quiet time for a live view.
         * @param viewTimeout positive timeout
         * @return this builder */
        public Builder viewTimeout(Duration viewTimeout) {
            this.viewTimeout = viewTimeout;
            return this;
        }

        /** Sets the maximum quiet time for a session.
         * @param sessionTimeout positive timeout
         * @return this builder */
        public Builder sessionTimeout(Duration sessionTimeout) {
            this.sessionTimeout = sessionTimeout;
            return this;
        }

        /**
         * Sets the maximum accepted request body size, including multipart framing.
         *
         * @param maxRequestBytes positive byte limit smaller than {@link Integer#MAX_VALUE}
         * @return this builder
         */
        public Builder maxRequestBytes(int maxRequestBytes) {
            this.maxRequestBytes = maxRequestBytes;
            return this;
        }

        /** Replaces the complete request-body spooling policy.
         * @param requestBodyPolicy body storage policy
         * @return this builder */
        public Builder requestBodyPolicy(RequestBodyPolicy requestBodyPolicy) {
            this.requestBodyPolicy = Objects.requireNonNull(requestBodyPolicy, "requestBodyPolicy");
            return this;
        }

        /** Sets the number of request bytes retained in heap before spilling to disk.
         * @param memoryThreshold non-negative threshold
         * @return this builder */
        public Builder requestBodyMemoryThreshold(int memoryThreshold) {
            requestBodyPolicy = new RequestBodyPolicy(
                    memoryThreshold,
                    requestBodyPolicy.maxMultipartTextFieldBytes(),
                    requestBodyPolicy.temporaryDirectory()
            );
            return this;
        }

        /** Sets the maximum decoded bytes in one non-file multipart field.
         * @param maximumBytes positive per-field limit
         * @return this builder */
        public Builder maxMultipartTextFieldBytes(int maximumBytes) {
            requestBodyPolicy = new RequestBodyPolicy(
                    requestBodyPolicy.memoryThreshold(),
                    maximumBytes,
                    requestBodyPolicy.temporaryDirectory()
            );
            return this;
        }

        /** Sets the existing writable directory used for request-scoped body files.
         * @param temporaryDirectory temporary directory
         * @return this builder */
        public Builder requestBodyTemporaryDirectory(Path temporaryDirectory) {
            requestBodyPolicy = new RequestBodyPolicy(
                    requestBodyPolicy.memoryThreshold(),
                    requestBodyPolicy.maxMultipartTextFieldBytes(),
                    temporaryDirectory
            );
            return this;
        }

        /** Sets the maximum number of requests executing in this JVM.
         * @param maxConcurrentRequests positive limit greater than the live-view limit
         * @return this builder */
        public Builder maxConcurrentRequests(int maxConcurrentRequests) {
            this.maxConcurrentRequests = maxConcurrentRequests;
            return this;
        }

        /** Sets the maximum number of retained browser views in this JVM.
         * @param maxLiveViews positive limit
         * @return this builder */
        public Builder maxLiveViews(int maxLiveViews) {
            this.maxLiveViews = maxLiveViews;
            return this;
        }

        /** Sets the maximum number of sessions retained by the configured repository.
         * @param maxSessions positive limit
         * @return this builder */
        public Builder maxSessions(int maxSessions) {
            this.maxSessions = maxSessions;
            return this;
        }

        /** Controls the {@code Secure} attribute on session cookies.
         * @param secureCookies whether cookies require HTTPS
         * @return this builder */
        public Builder secureCookies(boolean secureCookies) {
            this.secureCookies = secureCookies;
            return this;
        }

        /**
         * Sets the policy sent on every HTML response, including pages, errors, and static HTML.
         *
         * @param contentSecurityPolicy non-blank policy without control characters
         * @return this builder
         */
        public Builder contentSecurityPolicy(String contentSecurityPolicy) {
            this.contentSecurityPolicy = validateContentSecurityPolicy(contentSecurityPolicy);
            return this;
        }

        /** Sets the factory for convention-discovered application instances.
         * @param instanceFactory instance factory
         * @return this builder */
        public Builder instanceFactory(InstanceFactory instanceFactory) {
            this.instanceFactory = instanceFactory;
            return this;
        }

        /**
         * Replaces the default bounded in-memory application cache.
         *
         * @param cache application-scoped cache implementation
         * @return this builder
         */
        public Builder cache(RootsCache cache) {
            this.cache = Objects.requireNonNull(cache, "cache");
            return this;
        }

        /**
         * Replaces the default node-local session repository.
         *
         * @param sessionRepository session lifecycle and value repository
         * @return this builder
         */
        public Builder sessionRepository(SessionRepository sessionRepository) {
            this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
            return this;
        }

        /** Replaces the default node-local live-view lease registry.
         * @param liveViewOwnership ownership registry
         * @return this builder */
        public Builder liveViewOwnership(LiveViewOwnership liveViewOwnership) {
            this.liveViewOwnership = Objects.requireNonNull(liveViewOwnership, "liveViewOwnership");
            return this;
        }

        /** Sets the provider that authenticates every session-bearing request.
         * @param authenticationProvider request identity provider
         * @return this builder */
        public Builder authenticationProvider(AuthenticationProvider authenticationProvider) {
            this.authenticationProvider = Objects.requireNonNull(authenticationProvider, "authenticationProvider");
            return this;
        }

        /** Sets the trusted reverse-proxy policy. The default ignores forwarding headers.
         * @param proxyPolicy proxy resolution policy
         * @return this builder
         */
        public Builder proxyPolicy(ProxyPolicy proxyPolicy) {
            this.proxyPolicy = Objects.requireNonNull(proxyPolicy, "proxyPolicy");
            return this;
        }

        /** Sets the pre-session request limiter. The default allows every request.
         * @param rateLimiter thread-safe rate limiter
         * @return this builder
         */
        public Builder rateLimiter(RateLimiter rateLimiter) {
            this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
            return this;
        }

        /** Registers a request-completion observer.
         * Observer failures are isolated from HTTP responses and other observers.
         * @param observer completion observer
         * @return this builder
         */
        public Builder observeRequests(RequestObserver observer) {
            requestObservers.add(Objects.requireNonNull(observer, "observer"));
            return this;
        }

        /**
         * Registers a named policy referenced by {@code @Authorize}.
         *
         * @param name stable annotation-facing policy name
         * @param policy application-owned policy implementation
         * @return this builder
         */
        public Builder authorize(String name, AuthorizationPolicy policy) {
            validatePolicyName(name);
            Objects.requireNonNull(policy, "policy");
            if (authorizationPolicies.putIfAbsent(name, policy) != null) {
                throw new IllegalArgumentException("Authorization policy is already registered: " + name);
            }
            return this;
        }

        /** Appends request middleware.
         * @param middleware middleware entry
         * @return this builder */
        public Builder use(Middleware middleware) {
            this.middleware.add(Objects.requireNonNull(middleware, "middleware"));
            return this;
        }

        /** Appends an exception mapper.
         * @param mapper exception mapper
         * @return this builder */
        public Builder mapExceptions(ExceptionMapper mapper) {
            this.exceptionMappers.add(Objects.requireNonNull(mapper, "mapper"));
            return this;
        }

        /** Appends a mapper for one exception type.
         * @param <E> failure type
         * @param type failure class
         * @param mapper mapping function
         * @return this builder */
        public <E extends Throwable> Builder mapException(
                Class<E> type,
                BiFunction<? super Request, ? super E, ? extends Response> mapper
        ) {
            return mapExceptions(ExceptionMapper.forType(type, mapper));
        }

        /** Applies supported command-line options.
         * @param arguments Roots command-line options
         * @return this builder */
        public Builder arguments(String... arguments) {
            for (var argument : arguments) {
                if (argument.startsWith("--port=")) {
                    port(Integer.parseInt(argument.substring("--port=".length())));
                } else if (argument.startsWith("--host=")) {
                    host(argument.substring("--host=".length()));
                } else if (argument.equals("--production")) {
                    development(false);
                } else if (!argument.isBlank()) {
                    throw new IllegalArgumentException("Unknown Roots option: " + argument);
                }
            }
            return this;
        }

        /** Validates and creates immutable configuration.
         * @return configuration */
        public RootsConfig build() {
            return new RootsConfig(
                    applicationClass,
                    pagesPackage,
                    apiPackage,
                    host,
                    port,
                    development,
                    viewTimeout,
                    sessionTimeout,
                    maxRequestBytes,
                    maxConcurrentRequests,
                    maxLiveViews,
                    maxSessions,
                    secureCookies,
                    instanceFactory,
                    authorizationPolicies,
                    middleware,
                    exceptionMappers,
                    cache,
                    contentSecurityPolicy,
                    sessionRepository,
                    requestObservers,
                    proxyPolicy,
                    rateLimiter,
                    requestBodyPolicy,
                    liveViewOwnership,
                    authenticationProvider
            );
        }
    }
}
