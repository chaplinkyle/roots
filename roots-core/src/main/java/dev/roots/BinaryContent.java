package dev.roots;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Repeatable binary content that may be backed by memory, a temporary file, or
 * an application-provided store.
 *
 * <p>Request-scoped instances are closed by Roots after the synchronous request
 * pipeline completes. Consumers that need uploaded data afterward must copy or
 * transfer it while handling the request.</p>
 */
public interface BinaryContent extends AutoCloseable {
    /** Returns the exact byte count.
     * @return content length */
    long size();

    /** Opens a new stream positioned at the first byte.
     * @return repeatable content stream
     * @throws IOException when the backing store cannot be read */
    InputStream openStream() throws IOException;

    /** Reports whether all content is currently retained in heap memory.
     * @return true for an in-memory backing store */
    boolean inMemory();

    /** Reads all content into a new byte array.
     * @return copied bytes
     * @throws UncheckedIOException when the backing store cannot be read */
    default byte[] readAllBytes() {
        if (size() > Integer.MAX_VALUE) {
            throw new IllegalStateException("Binary content is too large for a byte array");
        }
        try (var input = openStream()) {
            var result = input.readAllBytes();
            if (result.length != size()) {
                throw new IOException("Binary content ended before its declared size");
            }
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read binary content", exception);
        }
    }

    /** Releases request-scoped backing resources.
     * @throws IOException when cleanup fails */
    @Override
    void close() throws IOException;

    /** Creates immutable in-memory content from copied bytes.
     * @param content source bytes
     * @return repeatable in-memory content */
    static BinaryContent of(byte[] content) {
        var copied = Objects.requireNonNull(content, "content").clone();
        return new BinaryContent() {
            @Override
            public long size() {
                return copied.length;
            }

            @Override
            public InputStream openStream() {
                return new ByteArrayInputStream(copied);
            }

            @Override
            public boolean inMemory() {
                return true;
            }

            @Override
            public void close() {
                // Immutable application-owned bytes need no cleanup.
            }
        };
    }
}
