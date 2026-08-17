package dev.roots;

import java.util.Objects;

/**
 * A reversible browser mutation applied while a server action is in flight.
 *
 * @param type supported mutation capability
 * @param target stable ID of the referenced target node
 * @param value bounded text value for {@link Type#TEXT} and {@link Type#VALUE}, otherwise {@code null}
 */
public record OptimisticEffect(Type type, String target, String value) {
    /** Supported script-free optimistic mutations. */
    public enum Type {
        /** Temporarily hides a node. */
        HIDE,
        /** Temporarily replaces text content. */
        TEXT,
        /** Temporarily replaces a control value. */
        VALUE,
        /** Temporarily disables a control. */
        DISABLE
    }

    /** Validates the bounded wire representation. */
    public OptimisticEffect {
        Objects.requireNonNull(type, "type");
        if (target == null || !target.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("Optimistic effect targets must be valid Roots ref IDs");
        }
        if (type == Type.TEXT || type == Type.VALUE) {
            Objects.requireNonNull(value, "value");
            if (value.length() > 16_384) {
                throw new IllegalArgumentException("Optimistic effect values must not exceed 16384 characters");
            }
        } else if (value != null) {
            throw new IllegalArgumentException(type + " optimistic effects do not accept a value");
        }
    }

    /** Immediately hides the referenced node.
     * @param target referenced node
     * @return hide effect */
    public static OptimisticEffect hide(Ref target) {
        return new OptimisticEffect(Type.HIDE, Objects.requireNonNull(target, "target").id(), null);
    }

    /** Immediately replaces the referenced node's text content.
     * @param target referenced node
     * @param value replacement text
     * @return text effect */
    public static OptimisticEffect text(Ref target, Object value) {
        return new OptimisticEffect(
                Type.TEXT,
                Objects.requireNonNull(target, "target").id(),
                String.valueOf(value)
        );
    }

    /** Immediately replaces the referenced form control's value.
     * @param target referenced control
     * @param value replacement value
     * @return value effect */
    public static OptimisticEffect value(Ref target, Object value) {
        return new OptimisticEffect(
                Type.VALUE,
                Objects.requireNonNull(target, "target").id(),
                String.valueOf(value)
        );
    }

    /** Immediately disables the referenced form control.
     * @param target referenced control
     * @return disable effect */
    public static OptimisticEffect disable(Ref target) {
        return new OptimisticEffect(Type.DISABLE, Objects.requireNonNull(target, "target").id(), null);
    }
}
