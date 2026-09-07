package com.chaplin.roots;

import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A validated response cookie that serializes to one {@code Set-Cookie} field.
 * Values are emitted literally and must already use the conservative RFC 6265
 * cookie-octet alphabet; Roots never performs ambiguous URL encoding.
 */
public final class ResponseCookie {
    private static final DateTimeFormatter COOKIE_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);

    /** Cross-site delivery policy. */
    public enum SameSite {
        /** Same-site requests only. */
        STRICT("Strict"),
        /** Same-site requests plus safe top-level cross-site navigation. */
        LAX("Lax"),
        /** Explicit cross-site delivery; requires {@code Secure}. */
        NONE("None");

        private final String wireValue;

        SameSite(String wireValue) {
            this.wireValue = wireValue;
        }
    }

    private final String name;
    private final String value;
    private final String path;
    private final String domain;
    private final Long maxAgeSeconds;
    private final Instant expires;
    private final boolean secure;
    private final boolean httpOnly;
    private final SameSite sameSite;
    private final boolean partitioned;

    private ResponseCookie(Builder builder) {
        name = CookieSyntax.requireName(builder.name);
        value = CookieSyntax.requireValue(builder.value);
        path = requirePath(builder.path);
        domain = builder.domain == null ? null : requireDomain(builder.domain);
        maxAgeSeconds = builder.maxAgeSeconds;
        expires = requireExpires(builder.expires);
        secure = builder.secure;
        httpOnly = builder.httpOnly;
        sameSite = Objects.requireNonNull(builder.sameSite, "sameSite");
        partitioned = builder.partitioned;
        if (sameSite == SameSite.NONE && !secure) {
            throw new IllegalArgumentException("SameSite=None cookies must be Secure");
        }
        if (partitioned && !secure) {
            throw new IllegalArgumentException("Partitioned cookies must be Secure");
        }
        validatePrefix();
    }

    /** Creates a conservative builder with root path, HttpOnly, and SameSite=Lax defaults.
     * The {@code Secure} attribute remains opt-in so localhost development works over HTTP.
     * @param name cookie name
     * @param value literal cookie value
     * @return cookie builder
     */
    public static Builder builder(String name, String value) {
        return new Builder(name, value);
    }

    /** Creates a builder that expires a root-path cookie immediately.
     * Adjust its path/domain/secure attributes to match the cookie being removed.
     * @param name cookie name
     * @return expiration builder
     */
    public static Builder expire(String name) {
        return builder(name, "").maxAgeSeconds(0).expires(Instant.EPOCH);
    }

    /** Returns the cookie name.
     * @return name */
    public String name() { return name; }

    /** Returns the literal cookie value.
     * @return value */
    public String value() { return value; }

    /** Returns the request path scope.
     * @return absolute cookie path */
    public String path() { return path; }

    /** Returns the optional domain scope.
     * @return ASCII domain, if configured */
    public Optional<String> domain() { return Optional.ofNullable(domain); }

    /** Returns the optional exact lifetime in seconds.
     * @return max-age seconds, if configured */
    public OptionalLong maxAgeSeconds() {
        return maxAgeSeconds == null ? OptionalLong.empty() : OptionalLong.of(maxAgeSeconds);
    }

    /** Returns the optional absolute expiry time.
     * @return expiry instant, if configured */
    public Optional<Instant> expires() { return Optional.ofNullable(expires); }

    /** Reports whether HTTPS-only delivery is requested.
     * @return secure flag */
    public boolean secure() { return secure; }

    /** Reports whether browser script access is forbidden.
     * @return HttpOnly flag */
    public boolean httpOnly() { return httpOnly; }

    /** Returns the explicit same-site policy.
     * @return same-site policy */
    public SameSite sameSite() { return sameSite; }

    /** Reports whether partitioned storage is requested.
     * @return partitioned flag */
    public boolean partitioned() { return partitioned; }

    /** Serializes one validated {@code Set-Cookie} field value.
     * Avoid logging the result because it contains the cookie value.
     * @return header field value */
    public String headerValue() {
        var header = new StringBuilder(name).append('=').append(value).append("; Path=").append(path);
        if (domain != null) header.append("; Domain=").append(domain);
        if (maxAgeSeconds != null) header.append("; Max-Age=").append(maxAgeSeconds);
        if (expires != null) header.append("; Expires=").append(COOKIE_DATE.format(expires));
        if (secure) header.append("; Secure");
        if (httpOnly) header.append("; HttpOnly");
        header.append("; SameSite=").append(sameSite.wireValue);
        if (partitioned) header.append("; Partitioned");
        return header.toString();
    }

    private void validatePrefix() {
        if ((name.startsWith("__Secure-") || name.startsWith("__Host-")
                || name.startsWith("__Http-") || name.startsWith("__Host-Http-")) && !secure) {
            throw new IllegalArgumentException("Secure cookie prefixes require the Secure attribute");
        }
        if ((name.startsWith("__Http-") || name.startsWith("__Host-Http-")) && !httpOnly) {
            throw new IllegalArgumentException("HTTP cookie prefixes require the HttpOnly attribute");
        }
        if ((name.startsWith("__Host-") || name.startsWith("__Host-Http-"))
                && (!path.equals("/") || domain != null)) {
            throw new IllegalArgumentException("Host cookie prefixes require Path=/ and no Domain attribute");
        }
    }

    private static String requirePath(String value) {
        Objects.requireNonNull(value, "cookie path");
        if (value.isEmpty() || value.length() > 1_024 || value.charAt(0) != '/'
                || value.chars().anyMatch(character -> character < 0x20 || character > 0x7e || character == ';')) {
            throw new IllegalArgumentException("Cookie paths must be absolute printable ASCII without semicolons");
        }
        return value;
    }

    private static String requireDomain(String value) {
        Objects.requireNonNull(value, "cookie domain");
        var normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.length() > 253 || normalized.startsWith(".") || normalized.endsWith(".")
                || !normalized.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) {
            throw new IllegalArgumentException("Cookie domains must be normalized ASCII host names");
        }
        for (var label : normalized.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                throw new IllegalArgumentException("Cookie domains must contain valid DNS labels");
            }
        }
        return normalized;
    }

    private static Instant requireExpires(Instant value) {
        if (value == null) return null;
        final int year;
        try {
            year = value.atZone(ZoneOffset.UTC).getYear();
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Cookie expiry is outside the supported calendar range", exception);
        }
        if (year < 1601 || year > 9999) {
            throw new IllegalArgumentException("Cookie expiry years must be between 1601 and 9999");
        }
        return value;
    }

    /** Mutable construction scope that produces an immutable validated cookie. */
    public static final class Builder {
        private final String name;
        private final String value;
        private String path = "/";
        private String domain;
        private Long maxAgeSeconds;
        private Instant expires;
        private boolean secure;
        private boolean httpOnly = true;
        private SameSite sameSite = SameSite.LAX;
        private boolean partitioned;

        private Builder(String name, String value) {
            this.name = CookieSyntax.requireName(name);
            this.value = CookieSyntax.requireValue(value);
        }

        /** Sets the absolute path scope.
         * @param path absolute cookie path
         * @return this builder */
        public Builder path(String path) { this.path = requirePath(path); return this; }

        /** Sets an explicit normalized ASCII domain scope.
         * Prefer host-only cookies unless subdomain sharing is required.
         * @param domain cookie domain
         * @return this builder */
        public Builder domain(String domain) { this.domain = requireDomain(domain); return this; }

        /** Sets an exact whole-second lifetime.
         * @param duration nonnegative whole-second lifetime
         * @return this builder */
        public Builder maxAge(Duration duration) {
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative() || duration.getNano() != 0) {
                throw new IllegalArgumentException("Cookie max age must be a nonnegative whole-second duration");
            }
            return maxAgeSeconds(duration.getSeconds());
        }

        /** Sets an exact lifetime in seconds; zero expires the cookie.
         * @param seconds nonnegative lifetime
         * @return this builder */
        public Builder maxAgeSeconds(long seconds) {
            if (seconds < 0) throw new IllegalArgumentException("Cookie max age must not be negative");
            maxAgeSeconds = seconds;
            return this;
        }

        /** Sets an absolute expiry time.
         * @param expires expiry instant
         * @return this builder */
        public Builder expires(Instant expires) { this.expires = requireExpires(expires); return this; }

        /** Controls HTTPS-only delivery.
         * @param secure secure flag
         * @return this builder */
        public Builder secure(boolean secure) { this.secure = secure; return this; }

        /** Controls browser-script access.
         * @param httpOnly HttpOnly flag
         * @return this builder */
        public Builder httpOnly(boolean httpOnly) { this.httpOnly = httpOnly; return this; }

        /** Sets the explicit same-site delivery policy.
         * @param sameSite policy
         * @return this builder */
        public Builder sameSite(SameSite sameSite) {
            this.sameSite = Objects.requireNonNull(sameSite, "sameSite");
            return this;
        }

        /** Controls partitioned browser storage; partitioned cookies must be Secure.
         * @param partitioned partitioned flag
         * @return this builder */
        public Builder partitioned(boolean partitioned) { this.partitioned = partitioned; return this; }

        /** Creates the immutable validated cookie.
         * @return response cookie */
        public ResponseCookie build() { return new ResponseCookie(this); }
    }
}
