package dev.roots;

public interface ApiRoute {
    default Response get(Request request) throws Exception {
        return Response.methodNotAllowed("GET");
    }

    default Response post(Request request) throws Exception {
        return Response.methodNotAllowed("POST");
    }

    default Response put(Request request) throws Exception {
        return Response.methodNotAllowed("PUT");
    }

    default Response patch(Request request) throws Exception {
        return Response.methodNotAllowed("PATCH");
    }

    default Response delete(Request request) throws Exception {
        return Response.methodNotAllowed("DELETE");
    }

    default Response handle(Request request) throws Exception {
        return switch (request.method()) {
            case "GET" -> get(request);
            case "POST" -> post(request);
            case "PUT" -> put(request);
            case "PATCH" -> patch(request);
            case "DELETE" -> delete(request);
            default -> Response.methodNotAllowed(request.method());
        };
    }
}
