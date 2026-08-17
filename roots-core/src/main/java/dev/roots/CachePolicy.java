package dev.roots;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Expiry and revalidation tags for one cached value.
 *
 * @param timeToLive positive value lifetime
 * @param tags immutable revalidation tags
 */
public record CachePolicy(Duration timeToLive, Set<String> tags) {
    /** Validates the lifetime and defensively freezes revalidation tags. */
    public CachePolicy {
        Objects.requireNonNull(timeToLive, "timeToLive");
        if (timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("Cache time-to-live must be positive");
        }
        var copiedTags = new LinkedHashSet<String>();
        Objects.requireNonNull(tags, "tags").forEach(tag -> {
            CacheValidation.tag(tag);
            copiedTags.add(tag);
        });
        tags = Set.copyOf(copiedTags);
    }

    /** Creates an untagged cache policy.
     *
     * @param timeToLive positive value lifetime
     * @return an untagged policy
     */
    public static CachePolicy forDuration(Duration timeToLive) {
        return new CachePolicy(timeToLive, Set.of());
    }

    /** Creates a tagged cache policy.
     *
     * @param timeToLive positive value lifetime
     * @param tags revalidation tags
     * @return a tagged policy
     */
    public static CachePolicy tagged(Duration timeToLive, String... tags) {
        return new CachePolicy(timeToLive, Set.of(tags));
    }
}
