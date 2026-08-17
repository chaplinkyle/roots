package dev.roots.html;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * A local image rendered through Roots' responsive, cacheable image optimizer.
 * Instances are configured fluently like {@link Element} values.
 */
public final class OptimizedImage implements Node {
    private final String source;
    private final String alternativeText;
    private final int width;
    private final int height;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private int[] widths;
    private int quality = 82;
    private String sizes;
    private boolean priority;

    OptimizedImage(String source, String alternativeText, int width, int height) {
        this.source = requireSource(source);
        this.alternativeText = Objects.requireNonNull(alternativeText, "alternativeText");
        if (width < 1 || width > 4096 || height < 1 || height > 4096) {
            throw new IllegalArgumentException("Image dimensions must be between 1 and 4096 pixels");
        }
        this.width = width;
        this.height = height;
        widths = new int[]{width};
    }

    /** Selects responsive output widths; the declared display width is always included.
     * @param values desired pixel widths
     * @return this image */
    public OptimizedImage widths(int... values) {
        Objects.requireNonNull(values, "values");
        var normalized = new TreeSet<Integer>();
        normalized.add(width);
        for (var value : values) {
            if (value < 1 || value > 4096) {
                throw new IllegalArgumentException("Responsive image widths must be between 1 and 4096 pixels");
            }
            normalized.add(value);
        }
        if (normalized.size() > 12) {
            throw new IllegalArgumentException("An optimized image may declare at most 12 widths");
        }
        widths = normalized.stream().mapToInt(Integer::intValue).toArray();
        return this;
    }

    /** Selects JPEG output quality.
     * @param value quality from 1 through 100
     * @return this image */
    public OptimizedImage quality(int value) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException("Image quality must be between 1 and 100");
        }
        quality = value;
        return this;
    }

    /** Supplies the browser's responsive-image sizes expression.
     * @param value non-blank sizes expression
     * @return this image */
    public OptimizedImage sizes(String value) {
        if (value == null || value.isBlank() || value.length() > 1024
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Image sizes must be printable, non-blank, and at most 1024 characters");
        }
        sizes = value;
        return this;
    }

    /** Prioritizes this image for likely largest-contentful-paint use.
     * @return this image */
    public OptimizedImage priority() {
        priority = true;
        return this;
    }

    /** Sets CSS classes.
     * @param className CSS class value
     * @return this image */
    public OptimizedImage className(String className) {
        return attr("class", className);
    }

    /** Adds or removes a non-structural HTML attribute.
     * @param name attribute name
     * @param value attribute value; {@code null} or {@code false} removes it
     * @return this image */
    public OptimizedImage attr(String name, Object value) {
        Objects.requireNonNull(name, "name");
        var normalized = name.toLowerCase(java.util.Locale.ROOT);
        if (!name.matches("[A-Za-z_:][A-Za-z0-9_.:-]*") || normalized.startsWith("on")) {
            throw new IllegalArgumentException("Invalid optimized-image attribute: " + name);
        }
        if (java.util.Set.of("src", "srcset", "alt", "width", "height", "sizes", "loading",
                "decoding", "fetchpriority").contains(normalized)) {
            throw new IllegalArgumentException("Roots controls optimized-image attribute: " + name);
        }
        if (value == null || Boolean.FALSE.equals(value)) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
        return this;
    }

    Element element() {
        var image = new Element("img")
                .attr("src", url(width))
                .attr("alt", alternativeText)
                .attr("width", width)
                .attr("height", height)
                .attr("decoding", "async")
                .attr("loading", priority ? "eager" : "lazy");
        if (priority) {
            image.attr("fetchpriority", "high");
        }
        if (sizes != null) {
            image.attr("sizes", sizes);
        }
        if (widths.length > 1) {
            image.attr("srcset", Arrays.stream(widths)
                    .mapToObj(candidate -> url(candidate) + " " + candidate + "w")
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        attributes.forEach(image::attr);
        return image;
    }

    private String url(int candidateWidth) {
        return "/_roots/image?src=" + encode(source) + "&w=" + candidateWidth + "&q=" + quality;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String requireSource(String value) {
        Objects.requireNonNull(value, "source");
        if (!value.startsWith("/") || value.startsWith("//") || value.indexOf('\\') >= 0
                || value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Image source must be a root-relative public asset path");
        }
        for (var segment : value.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || !segment.matches("[A-Za-z0-9._~!$&'+,;=@%-]+")) {
                throw new IllegalArgumentException("Image source contains an invalid path segment");
            }
        }
        var lower = value.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
            throw new IllegalArgumentException("Optimized images must use png, jpg, or jpeg");
        }
        return value;
    }
}
