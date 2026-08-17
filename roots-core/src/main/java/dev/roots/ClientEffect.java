package dev.roots;

import java.util.Objects;

/** A bounded browser-side effect returned by a server action.
 * @param type effect kind
 * @param target reference identifier, when required
 * @param value effect value, when required */
public record ClientEffect(Type type, String target, String value) {
    private static final int MAX_CLIPBOARD_LENGTH = 16_384;
    private static final int MAX_ANNOUNCEMENT_LENGTH = 2_048;

    /** Supported browser-effect kinds. */
    public enum Type {
        /** Moves focus to a referenced element. */
        FOCUS,
        /** Removes focus from a referenced element when it owns focus. */
        BLUR,
        /** Focuses and selects editable text in a referenced element. */
        SELECT_TEXT,
        /** Scrolls a referenced element into view. */
        SCROLL_INTO_VIEW,
        /** Copies a value to the browser clipboard. */
        COPY_TO_CLIPBOARD,
        /** Announces non-urgent text through the framework's polite ARIA live region. */
        ANNOUNCE_POLITE,
        /** Announces urgent text through the framework's assertive ARIA live region. */
        ANNOUNCE_ASSERTIVE,
        /** Animates the authoritative DOM commit with the browser View Transition API. */
        VIEW_TRANSITION
    }

    /** Validates the bounded wire representation. */
    public ClientEffect {
        Objects.requireNonNull(type, "type");
        switch (type) {
            case FOCUS, BLUR, SELECT_TEXT, SCROLL_INTO_VIEW -> {
                if (target == null || !target.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
                    throw new IllegalArgumentException(type + " effects require a valid Roots ref ID");
                }
                if (value != null) {
                    throw new IllegalArgumentException(type + " effects do not accept a value");
                }
            }
            case COPY_TO_CLIPBOARD -> {
                if (target != null) {
                    throw new IllegalArgumentException("COPY_TO_CLIPBOARD effects do not accept a target");
                }
                Objects.requireNonNull(value, "value");
                if (value.length() > MAX_CLIPBOARD_LENGTH) {
                    throw new IllegalArgumentException("Clipboard values must not exceed 16384 characters");
                }
            }
            case ANNOUNCE_POLITE, ANNOUNCE_ASSERTIVE -> {
                if (target != null) {
                    throw new IllegalArgumentException(type + " effects do not accept a target");
                }
                Objects.requireNonNull(value, "value");
                if (value.isBlank() || value.length() > MAX_ANNOUNCEMENT_LENGTH) {
                    throw new IllegalArgumentException(
                            "Browser announcements must be nonblank and not exceed 2048 characters"
                    );
                }
            }
            case VIEW_TRANSITION -> {
                if (target != null || value != null) {
                    throw new IllegalArgumentException("VIEW_TRANSITION effects do not accept a target or value");
                }
            }
        }
    }

    /** Creates a focus effect.
     * @param ref target element
     * @return focus effect */
    public static ClientEffect focus(Ref ref) {
        return new ClientEffect(Type.FOCUS, ref.id(), null);
    }

    /** Creates a blur effect.
     * @param ref target element
     * @return blur effect */
    public static ClientEffect blur(Ref ref) {
        return new ClientEffect(Type.BLUR, ref.id(), null);
    }

    /** Creates an editable-text selection effect.
     * @param ref target input, textarea, or editable element
     * @return selection effect */
    public static ClientEffect selectText(Ref ref) {
        return new ClientEffect(Type.SELECT_TEXT, ref.id(), null);
    }

    /** Creates a scroll-into-view effect.
     * @param ref target element
     * @return scroll effect */
    public static ClientEffect scrollIntoView(Ref ref) {
        return new ClientEffect(Type.SCROLL_INTO_VIEW, ref.id(), null);
    }

    /** Creates a clipboard-copy effect.
     * @param value text to copy
     * @return clipboard effect */
    public static ClientEffect copyToClipboard(String value) {
        return new ClientEffect(Type.COPY_TO_CLIPBOARD, null, value);
    }

    /** Creates a polite accessibility announcement.
     * @param message announcement text
     * @return polite announcement effect */
    public static ClientEffect announce(String message) {
        return new ClientEffect(Type.ANNOUNCE_POLITE, null, message);
    }

    /** Creates an assertive accessibility announcement for time-sensitive information.
     * @param message announcement text
     * @return assertive announcement effect */
    public static ClientEffect announceAssertively(String message) {
        return new ClientEffect(Type.ANNOUNCE_ASSERTIVE, null, message);
    }

    /** Creates a progressive-enhancement view-transition request.
     * @return view-transition effect */
    public static ClientEffect viewTransition() {
        return new ClientEffect(Type.VIEW_TRANSITION, null, null);
    }
}
