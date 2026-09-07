package com.chaplin.roots.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API route as independent of Roots browser sessions. Requests still
 * pass through authentication, authorization, limits, middleware, and tracing,
 * but never load/create/touch a session or set a Roots session cookie. Accessing
 * session identity or values fails explicitly. Use verified Bearer credentials,
 * container identity, or signed webhooks for machine authentication.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Stateless {
}
