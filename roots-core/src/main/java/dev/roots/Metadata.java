package dev.roots;

import java.util.List;

public record Metadata(String title, String description, List<String> stylesheets) {
    public static final Metadata DEFAULT = new Metadata("Roots", "", List.of());

    public Metadata {
        title = title == null || title.isBlank() ? "Roots" : title;
        description = description == null ? "" : description;
        stylesheets = stylesheets == null ? List.of() : List.copyOf(stylesheets);
    }

    public static Metadata of(String title, String description, String... stylesheets) {
        return new Metadata(title, description, List.of(stylesheets));
    }
}
