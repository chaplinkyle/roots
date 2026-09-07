package com.chaplin.roots;

/** Handles convention-routed HTTP API requests. */
public interface ApiRoute {
    /** Handles GET.
     * @param request request context
     * @return HTTP response
     * @throws Exception when route code fails */
    default Response get(Request request) throws Exception {
        return ApiRouteMethods.methodNotAllowed(this, "GET");
    }

    /** Handles HEAD by creating the GET representation without sending its body.
     * Override when calculating the GET representation is itself undesirable.
     * @param request request context
     * @return HTTP response metadata
     * @throws Exception when route code fails */
    default Response head(Request request) throws Exception {
        return ApiRouteMethods.supportsGet(this)
                ? get(request)
                : ApiRouteMethods.methodNotAllowed(this, "HEAD");
    }

    /** Handles POST.
     * @param request request context
     * @return HTTP response
     * @throws Exception when route code fails */
    default Response post(Request request) throws Exception {
        return ApiRouteMethods.methodNotAllowed(this, "POST");
    }

    /** Handles PUT.
     * @param request request context
     * @return HTTP response
     * @throws Exception when route code fails */
    default Response put(Request request) throws Exception {
        return ApiRouteMethods.methodNotAllowed(this, "PUT");
    }

    /** Handles PATCH.
     * @param request request context
     * @return HTTP response
     * @throws Exception when route code fails */
    default Response patch(Request request) throws Exception {
        return ApiRouteMethods.methodNotAllowed(this, "PATCH");
    }

    /** Handles DELETE.
     * @param request request context
     * @return HTTP response
     * @throws Exception when route code fails */
    default Response delete(Request request) throws Exception {
        return ApiRouteMethods.methodNotAllowed(this, "DELETE");
    }

    /** Handles OPTIONS with an empty response and the methods implemented by this route.
     * Override to add route-specific metadata such as an application CORS policy.
     * @param request request context
     * @return HTTP response with an {@code Allow} header
     * @throws Exception when route code fails */
    default Response options(Request request) throws Exception {
        return ApiRouteMethods.options(this);
    }

    /** Dispatches a request to its HTTP-method handler.
     * @param request request context
     * @return HTTP response
     * @throws Exception when route code fails */
    default Response handle(Request request) throws Exception {
        return switch (request.method()) {
            case "GET" -> get(request);
            case "HEAD" -> head(request);
            case "POST" -> post(request);
            case "PUT" -> put(request);
            case "PATCH" -> patch(request);
            case "DELETE" -> delete(request);
            case "OPTIONS" -> options(request);
            default -> ApiRouteMethods.methodNotAllowed(this, request.method());
        };
    }
}
