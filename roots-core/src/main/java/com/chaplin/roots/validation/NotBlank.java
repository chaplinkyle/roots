package com.chaplin.roots.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Requires a submitted string to contain non-whitespace text. */
@Documented
@FormConstraint(validatedBy = BuiltInConstraintValidators.NotBlankValidator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface NotBlank {
    /** Returns the validation message.
     * @return validation message */
    String message() default "Enter a value.";
}
