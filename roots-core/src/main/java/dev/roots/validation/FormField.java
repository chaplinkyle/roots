package dev.roots.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Maps a record component to a submitted form field. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface FormField {
    /**
     * Returns the submitted field name, or an empty value to use the record-component name.
     *
     * @return submitted field name override
     */
    String value() default "";

    /**
     * Returns whether surrounding whitespace is stripped from submitted text before conversion.
     *
     * @return whether to strip submitted text
     */
    boolean trim() default false;
}
