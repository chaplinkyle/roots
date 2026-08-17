package dev.roots;

import java.net.URI;

/** Optional browser-head metadata beyond the legacy title, description, and asset declarations.
 * Values are rendered as escaped, framework-managed tags and synchronized after live updates and
 * client navigation.
 *
 * @param canonical canonical page URL
 * @param robots search-engine directives
 * @param themeColor browser theme color
 * @param openGraph social preview metadata */
public record HeadMetadata(
        String canonical,
        String robots,
        String themeColor,
        OpenGraphMetadata openGraph
) {
    /** Empty extended head metadata. */
    public static final HeadMetadata EMPTY = new HeadMetadata("", "", "", OpenGraphMetadata.EMPTY);

    /** Normalizes nullable values and validates the canonical URL. */
    public HeadMetadata {
        canonical = MetadataUrls.webUrl(canonical, "Canonical URL");
        robots = text(robots);
        themeColor = text(themeColor);
        openGraph = openGraph == null ? OpenGraphMetadata.EMPTY : openGraph;
    }

    /** Creates empty head metadata for fluent customization. */
    public HeadMetadata() {
        this("", "", "", OpenGraphMetadata.EMPTY);
    }

    /** Returns a copy with a canonical URL.
     * @param value absolute HTTP(S) or application-root-relative URL
     * @return updated metadata */
    public HeadMetadata withCanonical(String value) {
        return new HeadMetadata(value, robots, themeColor, openGraph);
    }

    /** Returns a copy with robots directives.
     * @param value directives such as {@code index, follow} or {@code noindex, nofollow}
     * @return updated metadata */
    public HeadMetadata withRobots(String value) {
        return new HeadMetadata(canonical, value, themeColor, openGraph);
    }

    /** Returns a copy with a browser theme color.
     * @param value CSS color accepted by the theme-color meta element
     * @return updated metadata */
    public HeadMetadata withThemeColor(String value) {
        return new HeadMetadata(canonical, robots, value, openGraph);
    }

    /** Returns a copy with Open Graph metadata.
     * @param value social preview metadata
     * @return updated metadata */
    public HeadMetadata withOpenGraph(OpenGraphMetadata value) {
        return new HeadMetadata(canonical, robots, themeColor, value);
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}

final class MetadataUrls {
    private MetadataUrls() {
    }

    static String webUrl(String value, String label) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            return "";
        }
        final URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + " must be an absolute HTTP(S) or application-root-relative URL", exception);
        }
        if (normalized.startsWith("/") && !normalized.startsWith("//")
                && !uri.isAbsolute() && uri.getRawAuthority() == null) {
            return normalized;
        }
        if (!uri.isAbsolute() || uri.getHost() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(label + " must be an absolute HTTP(S) or application-root-relative URL");
        }
        return normalized;
    }
}
