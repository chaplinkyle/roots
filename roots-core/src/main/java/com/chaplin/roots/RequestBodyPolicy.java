package com.chaplin.roots;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Heap and temporary-storage policy for repeatable HTTP request bodies.
 *
 * @param memoryThreshold bytes retained in heap before the request spills to disk
 * @param maxMultipartTextFieldBytes maximum decoded bytes in one non-file multipart field
 * @param temporaryDirectory existing writable directory for request-scoped files
 */
public record RequestBodyPolicy(
        int memoryThreshold,
        int maxMultipartTextFieldBytes,
        Path temporaryDirectory
) {
    /** Default in-memory threshold. */
    public static final int DEFAULT_MEMORY_THRESHOLD = 65_536;
    /** Default per-field multipart text limit. */
    public static final int DEFAULT_MAX_MULTIPART_TEXT_FIELD_BYTES = 1_048_576;

    /** Validates and normalizes the policy. */
    public RequestBodyPolicy {
        if (memoryThreshold < 0) {
            throw new IllegalArgumentException("Request-body memory threshold must not be negative");
        }
        if (maxMultipartTextFieldBytes < 1) {
            throw new IllegalArgumentException("Multipart text-field limit must be positive");
        }
        temporaryDirectory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(temporaryDirectory) || !Files.isWritable(temporaryDirectory)) {
            throw new IllegalArgumentException("Request-body temporary directory must exist and be writable");
        }
    }

    /** Creates the default policy using the JVM temporary directory.
     * @return default request-body policy */
    public static RequestBodyPolicy defaults() {
        return new RequestBodyPolicy(
                DEFAULT_MEMORY_THRESHOLD,
                DEFAULT_MAX_MULTIPART_TEXT_FIELD_BYTES,
                Path.of(System.getProperty("java.io.tmpdir"))
        );
    }
}
