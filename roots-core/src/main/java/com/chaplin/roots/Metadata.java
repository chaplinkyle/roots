package com.chaplin.roots;

import java.util.List;

/** Browser document metadata supplied by a page.
 * @param title document title
 * @param description document description
 * @param stylesheets stylesheet URLs
 * @param fonts local web-font declarations */
public record Metadata(String title, String description, List<String> stylesheets, List<WebFont> fonts) {
    /** Default metadata used by pages that do not override it. */
    public static final Metadata DEFAULT = new Metadata("Roots", "", List.of(), List.of());

    /** Normalizes nullable metadata values and copies stylesheet URLs. */
    public Metadata {
        title = title == null || title.isBlank() ? "Roots" : title;
        description = description == null ? "" : description;
        stylesheets = stylesheets == null ? List.of() : List.copyOf(stylesheets);
        fonts = fonts == null ? List.of() : List.copyOf(fonts);
    }

    /** Preserves the original metadata constructor for applications without fonts.
     * @param title document title
     * @param description document description
     * @param stylesheets stylesheet URLs */
    public Metadata(String title, String description, List<String> stylesheets) {
        this(title, description, stylesheets, List.of());
    }

    /** Creates metadata while conveniently accepting stylesheet varargs.
     * @param title document title
     * @param description document description
     * @param stylesheets stylesheet URLs
     * @return metadata */
    public static Metadata of(String title, String description, String... stylesheets) {
        return new Metadata(title, description, List.of(stylesheets), List.of());
    }

    /** Returns a copy with local web fonts.
     * @param fonts font declarations
     * @return updated metadata */
    public Metadata withFonts(WebFont... fonts) {
        return new Metadata(title, description, stylesheets, List.of(fonts));
    }
}
