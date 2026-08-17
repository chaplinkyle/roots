package dev.roots.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/** Declares one local web font inside {@link PageMetadata}. */
@Target({ElementType.ANNOTATION_TYPE})
public @interface PageFont {
    /** Returns the CSS font-family name.
     * @return family name */
    String family();
    /** Returns the root-relative font asset path.
     * @return public font path */
    String source();
    /** Returns the CSS font weight or variable-font range.
     * @return font weight */
    String weight() default "400";
    /** Returns the CSS font style.
     * @return font style */
    String style() default "normal";
    /** Returns the CSS font-display strategy.
     * @return display strategy */
    String display() default "swap";
    /** Returns whether the font file should be preloaded.
     * @return preload flag */
    boolean preload() default false;
}
