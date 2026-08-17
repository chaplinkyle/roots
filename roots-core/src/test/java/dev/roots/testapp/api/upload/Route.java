package dev.roots.testapp.api.upload;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;

import java.util.HexFormat;

public final class Route implements ApiRoute {
    public Route(String ignored) {
    }

    @Override
    public Response post(Request request) {
        var files = request.files("attachments");
        if (files.isEmpty()) {
            return Response.text(422, "missing attachments");
        }
        var first = files.getFirst();
        return Response.text(200,
                request.formValue("title").orElse("")
                        + "|" + files.size()
                        + "|" + first.filename()
                        + "|" + first.contentType()
                        + "|" + first.size()
                        + "|" + HexFormat.of().formatHex(first.content())
                        + "|" + files.getLast().filename());
    }
}
