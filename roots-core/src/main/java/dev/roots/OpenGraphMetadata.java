package dev.roots;

/** Open Graph metadata used when a page is shared on social and collaboration platforms.
 * Empty values are omitted from the rendered document. When Open Graph metadata is present,
 * the page title and description are used as fallbacks for an omitted title or description.
 *
 * @param title social preview title
 * @param description social preview description
 * @param type Open Graph object type, such as {@code website} or {@code article}
 * @param url canonical URL represented by the object
 * @param image preview image URL
 * @param imageAlt accessible description of the preview image
 * @param siteName site or product name */
public record OpenGraphMetadata(
        String title,
        String description,
        String type,
        String url,
        String image,
        String imageAlt,
        String siteName
) {
    /** Empty Open Graph metadata. */
    public static final OpenGraphMetadata EMPTY = new OpenGraphMetadata("", "", "", "", "", "", "");

    /** Normalizes nullable values and validates URL-bearing fields. */
    public OpenGraphMetadata {
        title = text(title);
        description = text(description);
        type = text(type);
        url = MetadataUrls.webUrl(url, "Open Graph URL");
        image = MetadataUrls.webUrl(image, "Open Graph image URL");
        imageAlt = text(imageAlt);
        siteName = text(siteName);
    }

    /** Creates empty Open Graph metadata for fluent customization. */
    public OpenGraphMetadata() {
        this("", "", "", "", "", "", "");
    }

    /** Returns whether every Open Graph value is empty.
     * @return {@code true} when no Open Graph tags should be emitted */
    public boolean isEmpty() {
        return title.isEmpty() && description.isEmpty() && type.isEmpty() && url.isEmpty()
                && image.isEmpty() && imageAlt.isEmpty() && siteName.isEmpty();
    }

    /** Returns a copy with a social preview title.
     * @param value title
     * @return updated metadata */
    public OpenGraphMetadata withTitle(String value) {
        return new OpenGraphMetadata(value, description, type, url, image, imageAlt, siteName);
    }

    /** Returns a copy with a social preview description.
     * @param value description
     * @return updated metadata */
    public OpenGraphMetadata withDescription(String value) {
        return new OpenGraphMetadata(title, value, type, url, image, imageAlt, siteName);
    }

    /** Returns a copy with an Open Graph object type.
     * @param value object type
     * @return updated metadata */
    public OpenGraphMetadata withType(String value) {
        return new OpenGraphMetadata(title, description, value, url, image, imageAlt, siteName);
    }

    /** Returns a copy with the represented object's URL.
     * @param value absolute HTTP(S) or application-root-relative URL
     * @return updated metadata */
    public OpenGraphMetadata withUrl(String value) {
        return new OpenGraphMetadata(title, description, type, value, image, imageAlt, siteName);
    }

    /** Returns a copy with a preview image URL.
     * @param value absolute HTTP(S) or application-root-relative URL
     * @return updated metadata */
    public OpenGraphMetadata withImage(String value) {
        return new OpenGraphMetadata(title, description, type, url, value, imageAlt, siteName);
    }

    /** Returns a copy with preview image alternative text.
     * @param value image description
     * @return updated metadata */
    public OpenGraphMetadata withImageAlt(String value) {
        return new OpenGraphMetadata(title, description, type, url, image, value, siteName);
    }

    /** Returns a copy with a site name.
     * @param value site name
     * @return updated metadata */
    public OpenGraphMetadata withSiteName(String value) {
        return new OpenGraphMetadata(title, description, type, url, image, imageAlt, value);
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
