package dev.roots;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Immutable untrusted client metadata and repeatable content for one multipart file. */
public final class UploadedFile {
    private final String filename;
    private final String contentType;
    private final BinaryContent content;

    /** Creates an in-memory upload from defensively copied bytes.
     * @param filename untrusted client filename
     * @param contentType untrusted client media type
     * @param content uploaded bytes */
    public UploadedFile(String filename, String contentType, byte[] content) {
        this(filename, contentType, BinaryContent.of(content));
    }

    /** Creates an upload from repeatable binary content.
     *
     * <p>Framework-created request content is valid only during the synchronous
     * request pipeline. Copy it with {@link #transferTo(Path)} when it must live
     * longer.</p>
     *
     * @param filename untrusted client filename
     * @param contentType untrusted client media type
     * @param content repeatable uploaded content */
    public UploadedFile(String filename, String contentType, BinaryContent content) {
        this.filename = validateFilename(filename);
        this.contentType = validateContentType(contentType);
        this.content = Objects.requireNonNull(content, "content");
    }

    /** Returns the untrusted client-supplied filename.
     * @return client filename */
    public String filename() {
        return filename;
    }

    /** Returns the untrusted client-supplied media type.
     * @return client media type */
    public String contentType() {
        return contentType;
    }

    /** Returns a defensive in-memory copy of the uploaded bytes.
     * @return copied content */
    public byte[] content() {
        return content.readAllBytes();
    }

    /** Opens a repeatable stream for the uploaded bytes.
     * @return content stream
     * @throws IOException when request-scoped content is closed or unreadable */
    public InputStream openStream() throws IOException {
        return content.openStream();
    }

    /** Returns the exact uploaded byte count.
     * @return byte count */
    public int size() {
        return Math.toIntExact(content.size());
    }

    /** Reports whether this upload is retained entirely in heap memory.
     * @return true for an in-memory backing store */
    public boolean inMemory() {
        return content.inMemory();
    }

    /** Copies the upload to an application-owned path, replacing an existing file.
     * @param destination destination chosen by trusted application code
     * @return normalized destination path
     * @throws IOException when copying fails */
    public Path transferTo(Path destination) throws IOException {
        var target = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        try (var input = openStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /** Decodes uploaded bytes as UTF-8 text.
     * @return decoded text */
    public String contentText() {
        return contentText(StandardCharsets.UTF_8);
    }

    /** Decodes uploaded bytes with a selected charset.
     * @param charset text decoding charset
     * @return uploaded bytes decoded as text */
    public String contentText(Charset charset) {
        return new String(content(), Objects.requireNonNull(charset, "charset"));
    }

    private static String validateFilename(String filename) {
        Objects.requireNonNull(filename, "filename");
        if (filename.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IllegalArgumentException("Uploaded filenames cannot contain control characters");
        }
        return filename;
    }

    private static String validateContentType(String contentType) {
        var value = Objects.requireNonNullElse(contentType, "application/octet-stream");
        if (value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid uploaded file content type");
        }
        return value;
    }
}
