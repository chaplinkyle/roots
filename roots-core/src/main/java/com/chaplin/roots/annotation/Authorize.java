package com.chaplin.roots.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires every named authorization policy before a page, layout, API route,
 * or {@link ServerAction} method may execute.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Authorize {
    /** Returns ordered policy names; every policy must allow execution.
     * @return policy names */
    String[] value();
}
