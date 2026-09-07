package com.chaplin.roots;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable, bounded browser metadata captured with a server action.
 *
 * @param type validated event type
 * @param key keyboard key value, when present
 * @param code physical keyboard code, when present
 * @param altKey whether Alt was held
 * @param controlKey whether Control was held
 * @param metaKey whether Meta was held
 * @param shiftKey whether Shift was held
 * @param button pointer button number, when present
 * @param clientX viewport-relative pointer X coordinate, when present
 * @param clientY viewport-relative pointer Y coordinate, when present
 */
public record BrowserEvent(
        Type type,
        Optional<String> key,
        Optional<String> code,
        boolean altKey,
        boolean controlKey,
        boolean metaKey,
        boolean shiftKey,
        OptionalInt button,
        OptionalInt clientX,
        OptionalInt clientY
) {
    private static final int MAX_TOKEN_LENGTH = 128;
    private static final int MAX_COORDINATE = 1_000_000;

    /** Validates the browser protocol value. */
    public BrowserEvent {
        Objects.requireNonNull(type, "type");
        key = bounded(key, "key");
        code = bounded(code, "code");
        button = Objects.requireNonNull(button, "button");
        clientX = coordinate(clientX, "clientX");
        clientY = coordinate(clientY, "clientY");
        if (button.isPresent() && (button.getAsInt() < -1 || button.getAsInt() > 31)) {
            throw new IllegalArgumentException("Browser event button must be between -1 and 31");
        }
    }

    /**
     * Creates an event without keyboard or pointer metadata.
     *
     * @param type validated event type
     * @return an empty event of that type
     */
    public static BrowserEvent empty(Type type) {
        return new BrowserEvent(
                type,
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                false,
                false,
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty()
        );
    }

    private static Optional<String> bounded(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(token -> {
            if (token.length() > MAX_TOKEN_LENGTH) {
                throw new IllegalArgumentException("Browser event " + name + " must not exceed 128 characters");
            }
        });
        return value;
    }

    private static OptionalInt coordinate(OptionalInt value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isPresent() && Math.abs((long) value.getAsInt()) > MAX_COORDINATE) {
            throw new IllegalArgumentException("Browser event " + name + " is outside the supported range");
        }
        return value;
    }

    /** Browser events supported by the Roots delegated action runtime. */
    public enum Type {
        /** Primary pointer or keyboard activation. */
        CLICK("click"),
        /** Double activation. */
        DOUBLE_CLICK("dblclick"),
        /** Form submission. */
        SUBMIT("submit"),
        /** Committed form-control value change. */
        CHANGE("change"),
        /** Immediate editable value input. */
        INPUT("input"),
        /** Keyboard key press. */
        KEY_DOWN("keydown"),
        /** Keyboard key release. */
        KEY_UP("keyup"),
        /** Element focus, delegated in capture mode. */
        FOCUS("focus"),
        /** Element blur, delegated in capture mode. */
        BLUR("blur"),
        /** Pointer press. */
        POINTER_DOWN("pointerdown"),
        /** Pointer release. */
        POINTER_UP("pointerup");

        private final String wireName;
        private static final Type[] SUPPORTED = values();

        Type(String wireName) {
            this.wireName = wireName;
        }

        /**
         * Returns the lowercase DOM name transported by Roots.
         *
         * @return wire event name
         */
        public String wireName() {
            return wireName;
        }

        /**
         * Resolves and validates a lowercase DOM event name.
         *
         * @param wireName lowercase DOM event name
         * @return matching supported type
         * @throws IllegalArgumentException when the event is unsupported
         */
        public static Type fromWireName(String wireName) {
            Objects.requireNonNull(wireName, "wireName");
            for (var type : SUPPORTED) {
                if (type.wireName.equals(wireName)) return type;
            }
            throw new IllegalArgumentException("Unsupported browser event: " + wireName);
        }
    }
}
