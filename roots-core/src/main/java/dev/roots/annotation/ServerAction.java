package dev.roots.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a void Java method as bindable to a browser event. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ServerAction {
    /** Returns the optional public action name.
     * @return declared name, or empty to use the method name */
    String value() default "";
}
