package com.chaplin.roots.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a void Java method as bindable to a browser event.
 * Validate input before business mutations. Handler validation failures remain
 * correctable; unexpected handler failures or failures rendering its result leave
 * the live view unable to accept further actions until a new view is opened.
 * An error response cannot prove that business writes rolled back. Applications
 * own durable operation identifiers, transaction boundaries, and result recovery.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ServerAction {
    /** Returns the optional public action name.
     * @return declared name, or empty to use the method name */
    String value() default "";
}
