package dev.roots.annotation;

import dev.roots.StaticPathProvider;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a public, deterministic page into static generation.
 *
 * <p>A positive revalidation interval enables incremental static regeneration.
 * Zero keeps the generated page until the application is redeployed.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Prerender {
    /** Returns the number of seconds between background regenerations.
     * @return zero for deployment-lifetime content, or a positive interval
     */
    long revalidateSeconds() default 0;

    /** Returns the provider for concrete dynamic-route parameters.
     * @return path provider type
     */
    Class<? extends StaticPathProvider> paths() default StaticPathProvider.None.class;
}
