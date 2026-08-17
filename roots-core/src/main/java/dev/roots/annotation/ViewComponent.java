package dev.roots.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marker annotation for reusable Roots components and tooling. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ViewComponent {
    /** Returns the optional tooling name for the component.
     * @return tooling name */
    String value() default "";
}
