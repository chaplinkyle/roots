package dev.roots;

import java.util.Objects;

/**
 * A local web font exposed through Roots' generated, cacheable font stylesheet.
 *
 * @param family CSS font-family name
 * @param source root-relative font file under {@code public/}
 * @param weight CSS font weight or variable-font range
 * @param style CSS font style
 * @param display CSS font-display strategy
 * @param preload whether the document should preload the font file
 */
public record WebFont(
        String family,
        String source,
        String weight,
        String style,
        String display,
        boolean preload
) {
    /** Validates a web-font declaration. */
    public WebFont {
        family = requireFamily(family);
        source = requireSource(source);
        weight = requireWeight(weight);
        style = requireChoice(style, "style", "normal", "italic", "oblique");
        display = requireChoice(display, "display", "auto", "block", "swap", "fallback", "optional");
    }

    /**
     * Creates a normal, weight-400, swap-displayed local font.
     * @param family CSS font-family name
     * @param source root-relative font file under {@code public/}
     * @return font declaration
     */
    public static WebFont of(String family, String source) {
        return new WebFont(family, source, "400", "normal", "swap", false);
    }

    /** Returns a copy with a CSS weight or variable-font range.
     * @param value weight such as {@code 400} or {@code 100 900}
     * @return updated declaration */
    public WebFont weight(String value) {
        return new WebFont(family, source, value, style, display, preload);
    }

    /** Returns a copy with the CSS font style.
     * @param value {@code normal}, {@code italic}, or {@code oblique}
     * @return updated declaration */
    public WebFont style(String value) {
        return new WebFont(family, source, weight, value, display, preload);
    }

    /** Returns a copy with the CSS font-display strategy.
     * @param value CSS font-display value
     * @return updated declaration */
    public WebFont display(String value) {
        return new WebFont(family, source, weight, style, value, preload);
    }

    /** Returns a copy that preloads the font file from the document head.
     * @return updated declaration */
    public WebFont preloaded() {
        return new WebFont(family, source, weight, style, display, true);
    }

    private static String requireFamily(String value) {
        Objects.requireNonNull(value, "family");
        if (!value.matches("[\\p{L}\\p{N} _.-]{1,80}")) {
            throw new IllegalArgumentException("Font family must contain 1-80 letters, digits, spaces, '_', '.', or '-'");
        }
        return value;
    }

    private static String requireSource(String value) {
        Objects.requireNonNull(value, "source");
        if (!value.startsWith("/") || value.startsWith("//") || value.indexOf('\\') >= 0
                || value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Font source must be a root-relative public asset path");
        }
        for (var segment : value.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || !segment.matches("[A-Za-z0-9._~!$&'+,;=@%-]+")) {
                throw new IllegalArgumentException("Font source contains an invalid path segment");
            }
        }
        var lower = value.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.endsWith(".woff2") || lower.endsWith(".woff")
                || lower.endsWith(".ttf") || lower.endsWith(".otf"))) {
            throw new IllegalArgumentException("Font source must use woff2, woff, ttf, or otf");
        }
        return value;
    }

    private static String requireWeight(String value) {
        Objects.requireNonNull(value, "weight");
        if (!value.matches("(?:normal|bold|[1-9]00)(?: [1-9]00)?")) {
            throw new IllegalArgumentException("Font weight must be normal, bold, 100-900, or a numeric range");
        }
        return value;
    }

    private static String requireChoice(String value, String name, String... choices) {
        Objects.requireNonNull(value, name);
        for (var choice : choices) {
            if (value.equals(choice)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported font " + name + ": " + value);
    }
}
