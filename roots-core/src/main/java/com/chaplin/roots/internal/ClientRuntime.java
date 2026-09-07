package com.chaplin.roots.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Browser assets are loaded once and packaged with the dependency-free core. */
final class ClientRuntime {
    static final String SOURCE = resource("roots.js");
    static final String CSS = resource("roots.css");
    static final String DEVELOPMENT_CSS = resource("roots-development.css");

    private ClientRuntime() {
    }

    private static String resource(String name) {
        try (var stream = ClientRuntime.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing Roots browser resource: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
