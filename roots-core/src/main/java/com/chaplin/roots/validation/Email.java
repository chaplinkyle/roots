package com.chaplin.roots.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Validates the basic shape of a nonblank email address. */
@Documented
@FormConstraint(validatedBy = BuiltInConstraintValidators.EmailValidator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Email {
    /** Returns the validation message.
     * @return validation message */
    String message() default "Enter a valid email address.";
}
