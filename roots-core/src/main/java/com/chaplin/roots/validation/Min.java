package com.chaplin.roots.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Requires a numeric value greater than or equal to a configured minimum. */
@Documented
@FormConstraint(validatedBy = BuiltInConstraintValidators.MinValidator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Min {
    /** Returns the inclusive minimum.
     * @return inclusive minimum */
    long value();

    /** Returns the validation message with an expandable value placeholder.
     * @return validation message; {@code {value}} is expanded */
    String message() default "Enter a value greater than or equal to {value}.";
}
