package com.chaplin.roots.spring.boot;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables the Roots server in a Spring Boot application.
 *
 * <p>The value is the package anchor used by Roots conventions. By default,
 * pages are discovered below {@code .pages} and API routes below {@code .api}
 * relative to that class.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(RootsApplicationRegistrar.class)
public @interface EnableRoots {
    /**
     * Returns the application class used as the Roots convention-scan anchor.
     *
     * @return application anchor class
     */
    Class<?> value();
}
