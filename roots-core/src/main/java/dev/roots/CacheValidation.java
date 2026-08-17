package dev.roots;

final class CacheValidation {
    private CacheValidation() {
    }

    static void key(String key) {
        if (key == null || key.isBlank() || key.length() > 1_024 || controls(key)) {
            throw new IllegalArgumentException("Cache keys must be non-blank, control-free, and at most 1024 characters");
        }
    }

    static void tag(String tag) {
        if (tag == null || tag.isBlank() || tag.length() > 256 || controls(tag)) {
            throw new IllegalArgumentException("Cache tags must be non-blank, control-free, and at most 256 characters");
        }
    }

    private static boolean controls(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }
}
