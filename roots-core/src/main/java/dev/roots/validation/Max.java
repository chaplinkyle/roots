package dev.roots.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Requires a numeric value less than or equal to a configured maximum. */
@Documented
@FormConstraint(validatedBy = BuiltInConstraintValidators.MaxValidator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Max {
    /** Returns the inclusive maximum.
     * @return inclusive maximum */
    long value();

    /** Returns the validation message with an expandable value placeholder.
     * @return validation message; {@code {value}} is expanded */
    String message() default "Enter a value less than or equal to {value}.";
}
