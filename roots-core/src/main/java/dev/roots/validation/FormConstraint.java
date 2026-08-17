package dev.roots.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a record-component annotation as a declarative form constraint. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface FormConstraint {
    /**
     * Returns the stateless validator used for the annotated record component.
     *
     * @return validator type with an accessible no-argument constructor
     */
    Class<? extends ConstraintValidator<?>> validatedBy();
}
