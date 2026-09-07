package com.chaplin.roots.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Configures validation behavior for a form-binding record. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FormModel {
    /**
     * Returns the accessible validation summary sent to the browser.
     *
     * @return validation summary
     */
    String message() default "Validation failed";
}
