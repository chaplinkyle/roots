package com.chaplin.roots.html;

/** ASCII grammars used on the render path, without allocating regex matchers. */
final class HtmlNames {
    private HtmlNames() {}
    private static boolean lower(char value) { return value >= 'a' && value <= 'z'; }
    private static boolean letter(char value) { return lower(value) || value >= 'A' && value <= 'Z'; }
    private static boolean digit(char value) { return value >= '0' && value <= '9'; }
    static boolean tag(String value) {
        if (value.isEmpty() || !lower(value.charAt(0))) return false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!lower(c) && !digit(c) && c != '-') return false;
        }
        return true;
    }
    static boolean attribute(String value) {
        if (value.isEmpty()) return false;
        char first = value.charAt(0);
        if (!letter(first) && first != '_' && first != ':') return false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!letter(c) && !digit(c) && c != '_' && c != '.' && c != ':' && c != '-') return false;
        }
        return true;
    }
    static boolean action(String value) {
        if (value.isEmpty() || !letter(value.charAt(0)) && !digit(value.charAt(0))) return false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!letter(c) && !digit(c) && c != '_' && c != '.' && c != ':' && c != '-') return false;
        }
        return true;
    }
}
