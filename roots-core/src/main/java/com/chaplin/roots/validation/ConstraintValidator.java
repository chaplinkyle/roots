package com.chaplin.roots.validation;

import java.lang.annotation.Annotation;
import java.util.Optional;

/** Validates one converted form value for an application or built-in constraint annotation.
 * @param <A> constraint annotation type */
@FunctionalInterface
public interface ConstraintValidator<A extends Annotation> {
    /**
     * Validates a converted record-component value.
     *
     * @param value converted value
     * @param constraint annotation configuration
     * @return an error message, or empty when valid
     */
    Optional<String> validate(Object value, A constraint);
}
