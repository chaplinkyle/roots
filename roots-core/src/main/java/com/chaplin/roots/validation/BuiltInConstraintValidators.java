package com.chaplin.roots.validation;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import java.util.regex.Pattern;

/** Validator implementations backing Roots' built-in form constraints. */
public final class BuiltInConstraintValidators {
    private BuiltInConstraintValidators() {
    }

    /** Validates {@link NotBlank}. */
    public static final class NotBlankValidator implements ConstraintValidator<NotBlank> {
        /** Creates the stateless validator. */
        public NotBlankValidator() {
        }

        @Override
        public Optional<String> validate(Object value, NotBlank constraint) {
            if (!(value instanceof CharSequence text)) {
                throw new IllegalArgumentException("@NotBlank requires a string record component");
            }
            return text.toString().isBlank() ? Optional.of(constraint.message()) : Optional.empty();
        }
    }

    /** Validates {@link Email}. */
    public static final class EmailValidator implements ConstraintValidator<Email> {
        private static final Pattern SHAPE = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

        /** Creates the stateless validator. */
        public EmailValidator() {
        }

        @Override
        public Optional<String> validate(Object value, Email constraint) {
            if (!(value instanceof CharSequence text)) {
                throw new IllegalArgumentException("@Email requires a string record component");
            }
            var candidate = text.toString();
            return candidate.isBlank() || SHAPE.matcher(candidate).matches()
                    ? Optional.empty()
                    : Optional.of(constraint.message());
        }
    }

    /** Validates {@link Size}. */
    public static final class SizeValidator implements ConstraintValidator<Size> {
        /** Creates the stateless validator. */
        public SizeValidator() {
        }

        @Override
        public Optional<String> validate(Object value, Size constraint) {
            if (constraint.min() < 0 || constraint.max() < constraint.min()) {
                throw new IllegalArgumentException("@Size requires 0 <= min <= max");
            }
            var size = switch (value) {
                case CharSequence text -> text.length();
                case Collection<?> collection -> collection.size();
                default -> -1;
            };
            if (size < 0) {
                throw new IllegalArgumentException("@Size requires a string or list record component");
            }
            return size >= constraint.min() && size <= constraint.max()
                    ? Optional.empty()
                    : Optional.of(constraint.message()
                    .replace("{min}", String.valueOf(constraint.min()))
                    .replace("{max}", String.valueOf(constraint.max())));
        }
    }

    /** Validates {@link Min}. */
    public static final class MinValidator implements ConstraintValidator<Min> {
        /** Creates the stateless validator. */
        public MinValidator() {
        }

        @Override
        public Optional<String> validate(Object value, Min constraint) {
            var number = number(value, "@Min");
            return number.compareTo(BigDecimal.valueOf(constraint.value())) >= 0
                    ? Optional.empty()
                    : Optional.of(constraint.message().replace("{value}", String.valueOf(constraint.value())));
        }
    }

    /** Validates {@link Max}. */
    public static final class MaxValidator implements ConstraintValidator<Max> {
        /** Creates the stateless validator. */
        public MaxValidator() {
        }

        @Override
        public Optional<String> validate(Object value, Max constraint) {
            var number = number(value, "@Max");
            return number.compareTo(BigDecimal.valueOf(constraint.value())) <= 0
                    ? Optional.empty()
                    : Optional.of(constraint.message().replace("{value}", String.valueOf(constraint.value())));
        }
    }

    private static BigDecimal number(Object value, String annotation) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(annotation + " requires a numeric record component");
        }
        return new BigDecimal(number.toString());
    }
}
