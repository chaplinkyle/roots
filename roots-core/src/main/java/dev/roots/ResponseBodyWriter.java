package dev.roots;

import java.io.IOException;
import java.io.OutputStream;

/** Writes one response body lazily after HTTP headers have been committed. */
@FunctionalInterface
public interface ResponseBodyWriter {
    /**
     * Writes the complete body synchronously without closing the supplied stream.
     * Implementations should open and close their own files, database cursors, or
     * object-store streams inside this call.
     *
     * @param output framework-owned response stream
     * @throws IOException when content cannot be produced or written
     */
    void writeTo(OutputStream output) throws IOException;
}
