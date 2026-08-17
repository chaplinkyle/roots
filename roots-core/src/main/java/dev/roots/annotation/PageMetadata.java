package dev.roots.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares static browser document metadata for a page class. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PageMetadata {
    /** Returns the document title.
     * @return title */
    String title();
    /** Returns the document description.
     * @return description */
    String description() default "";
    /** Returns stylesheet URLs loaded by the page.
     * @return stylesheet URLs */
    String[] stylesheets() default {};
    /** Returns local web fonts loaded by the page.
     * @return font declarations */
    PageFont[] fonts() default {};
    /** Returns the canonical page URL.
     * @return absolute HTTP(S) or application-root-relative URL */
    String canonical() default "";
    /** Returns search-engine directives.
     * @return robots directives */
    String robots() default "";
    /** Returns the browser theme color.
     * @return CSS color */
    String themeColor() default "";
    /** Returns the Open Graph preview title.
     * @return social preview title */
    String openGraphTitle() default "";
    /** Returns the Open Graph preview description.
     * @return social preview description */
    String openGraphDescription() default "";
    /** Returns the Open Graph object type.
     * @return object type */
    String openGraphType() default "";
    /** Returns the Open Graph object URL.
     * @return absolute HTTP(S) or application-root-relative URL */
    String openGraphUrl() default "";
    /** Returns the Open Graph preview image URL.
     * @return absolute HTTP(S) or application-root-relative URL */
    String openGraphImage() default "";
    /** Returns accessible text for the Open Graph preview image.
     * @return image alternative text */
    String openGraphImageAlt() default "";
    /** Returns the Open Graph site name.
     * @return site name */
    String openGraphSiteName() default "";
}
