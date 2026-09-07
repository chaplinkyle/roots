package com.chaplin.roots.servlet.fixture.api.echo;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;
import com.chaplin.roots.UploadedFile;

import java.io.IOException;

public final class Route implements ApiRoute {
    private static volatile UploadedFile lastUpload;

    @Override
    public Response post(Request request) throws IOException {
        if (request.file("attachment").isPresent()) {
            var upload = request.file("attachment").orElseThrow();
            lastUpload = upload;
            long read = 0;
            try (var input = upload.openStream()) {
                while (input.read() >= 0) {
                    read++;
                }
            }
            return Response.json(200, "{\"requestInMemory\":" + request.bodyInMemory()
                    + ",\"uploadInMemory\":" + upload.inMemory()
                    + ",\"bytes\":" + read + "}");
        }
        return Response.json(200, "{\"body\":" + json(request.bodyText())
                + ",\"query\":" + json(request.queryValue("value").orElse(""))
                + ",\"header\":" + json(request.header("X-Fixture").orElse("")) + "}");
    }

    public static UploadedFile lastUpload() {
        return lastUpload;
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
