package ${package}.api.health;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;

public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        return Response.json(200, "{\"status\":\"ok\",\"framework\":\"roots\"}");
    }
}
