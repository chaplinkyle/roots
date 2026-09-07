package com.chaplin.roots.testapp.api.spool;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;
import com.chaplin.roots.UploadedFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Route implements ApiRoute {
    private static volatile UploadedFile lastUpload;

    public Route(String ignoredDependency) {
    }

    @Override
    public Response post(Request request) throws IOException {
        var upload = request.file("attachment").orElseThrow();
        lastUpload = upload;
        var digest = sha256(upload);
        return Response.json(200, "{\"requestBytes\":" + request.bodySize()
                + ",\"requestInMemory\":" + request.bodyInMemory()
                + ",\"uploadBytes\":" + upload.size()
                + ",\"uploadInMemory\":" + upload.inMemory()
                + ",\"title\":\"" + request.formValue("title").orElseThrow()
                + "\",\"sha256\":\"" + digest + "\"}");
    }

    public static UploadedFile lastUpload() {
        return lastUpload;
    }

    public static void reset() {
        lastUpload = null;
    }

    private static String sha256(UploadedFile upload) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        try (var input = upload.openStream()) {
            var buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
