package com.chaplin.roots;

/**
 * Intercepts a non-streaming Roots HTTP request before the framework dispatches
 * it. Middleware may short-circuit with a response or delegate to the next
 * entry. The SSE transport is authenticated directly with its session, view,
 * and CSRF tokens.
 */
@FunctionalInterface
public interface Middleware {
    /** Intercepts or delegates a request.
     * @param request immutable request context
     * @param chain remaining middleware and route
     * @return resulting response
     * @throws Exception when middleware or delegated code fails */
    Response handle(Request request, Chain chain) throws Exception;

    /** Continuation for the remaining request pipeline. */
    @FunctionalInterface
    interface Chain {
        /** Invokes the next pipeline entry.
         * @return resulting response
         * @throws Exception when downstream code fails */
        Response next() throws Exception;
    }
}
