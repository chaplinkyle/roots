package dev.roots;

public record ClientEffect(Type type, String target, String value) {
    public enum Type {
        FOCUS,
        SCROLL_INTO_VIEW,
        COPY_TO_CLIPBOARD
    }

    public static ClientEffect focus(Ref ref) {
        return new ClientEffect(Type.FOCUS, ref.id(), null);
    }

    public static ClientEffect scrollIntoView(Ref ref) {
        return new ClientEffect(Type.SCROLL_INTO_VIEW, ref.id(), null);
    }

    public static ClientEffect copyToClipboard(String value) {
        return new ClientEffect(Type.COPY_TO_CLIPBOARD, null, value);
    }
}
