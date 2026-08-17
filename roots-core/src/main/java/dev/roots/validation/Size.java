package dev.roots.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Constrains the size of a submitted string or list. */
@Documented
@FormConstraint(validatedBy = BuiltInConstraintValidators.SizeValidator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Size {
    /** Returns the inclusive minimum size.
     * @return inclusive minimum size */
    int min() default 0;

    /** Returns the inclusive maximum size.
     * @return inclusive maximum size */
    int max() default Integer.MAX_VALUE;

    /** Returns the validation message with expandable size placeholders.
     * @return validation message; {@code {min}} and {@code {max}} are expanded */
    String message() default "Use between {min} and {max} values.";
}
