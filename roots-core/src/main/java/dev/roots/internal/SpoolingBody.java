package dev.roots.internal;

import dev.roots.BinaryContent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;

/** Heap-bounded, repeatable request content with deterministic temporary-file cleanup. */
final class SpoolingBody implements BinaryContent {
    static final int DEFAULT_MEMORY_THRESHOLD = 65_536;

    private final byte[] memory;
    private final Path temporaryFile;
    private final long size;
    private final Object lifecycle = new Object();
    private final Set<TrackedInputStream> openStreams = new LinkedHashSet<>();
    private boolean closed;

    private SpoolingBody(byte[] memory, Path temporaryFile, long size) {
        this.memory = memory;
        this.temporaryFile = temporaryFile;
        this.size = size;
    }

    static SpoolingBody read(InputStream input, int maximumBytes, int memoryThreshold) throws IOException {
        return read(input, maximumBytes, memoryThreshold, null);
    }

    static SpoolingBody read(
            InputStream input,
            int maximumBytes,
            int memoryThreshold,
            Path temporaryDirectory
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        if (maximumBytes < 0 || memoryThreshold < 0 || memoryThreshold > maximumBytes) {
            throw new IllegalArgumentException("Invalid request-body spool limits");
        }
        var memory = new ByteArrayOutputStream(Math.min(memoryThreshold, 8_192));
        Path temporaryFile = null;
        OutputStream output = memory;
        long count = 0;
        try (input) {
            var buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                count += read;
                if (count > maximumBytes) {
                    throw new HttpSupport.RequestTooLargeException();
                }
                if (temporaryFile == null && count > memoryThreshold) {
                    temporaryFile = temporaryDirectory == null
                            ? Files.createTempFile("roots-request-", ".body")
                            : Files.createTempFile(temporaryDirectory, "roots-request-", ".body");
                    output = Files.newOutputStream(temporaryFile, StandardOpenOption.WRITE);
                    memory.writeTo(output);
                    memory.reset();
                }
                output.write(buffer, 0, read);
            }
            output.close();
            return temporaryFile == null
                    ? new SpoolingBody(memory.toByteArray(), null, count)
                    : new SpoolingBody(null, temporaryFile, count);
        } catch (Throwable failure) {
            try {
                output.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException deleteFailure) {
                    failure.addSuppressed(deleteFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public InputStream openStream() throws IOException {
        return openStream(0, size);
    }

    BinaryContent slice(long offset, long length) {
        if (offset < 0 || length < 0 || offset > size - length) {
            throw new IndexOutOfBoundsException("Invalid binary-content slice");
        }
        return new BinaryContent() {
            @Override
            public long size() {
                return length;
            }

            @Override
            public InputStream openStream() throws IOException {
                return SpoolingBody.this.openStream(offset, length);
            }

            @Override
            public boolean inMemory() {
                return SpoolingBody.this.inMemory();
            }

            @Override
            public void close() {
                // The owning request closes the shared root body.
            }
        };
    }

    @Override
    public boolean inMemory() {
        return temporaryFile == null;
    }

    Path temporaryFile() {
        return temporaryFile;
    }

    @Override
    public void close() throws IOException {
        final Set<TrackedInputStream> streams;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            streams = Set.copyOf(openStreams);
            openStreams.clear();
        }
        IOException failure = null;
        for (var stream : streams) {
            try {
                stream.closeFromOwner();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (temporaryFile != null) {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private InputStream openStream(long offset, long length) throws IOException {
        synchronized (lifecycle) {
            if (closed) {
                throw new IOException("Request body is closed");
            }
            final InputStream source;
            if (memory != null) {
                source = new ByteArrayInputStream(memory, Math.toIntExact(offset), Math.toIntExact(length));
            } else {
                var channel = FileChannel.open(temporaryFile, StandardOpenOption.READ);
                try {
                    channel.position(offset);
                    source = new LimitedInputStream(Channels.newInputStream(channel), length);
                } catch (Throwable failure) {
                    channel.close();
                    throw failure;
                }
            }
            var tracked = new TrackedInputStream(source);
            openStreams.add(tracked);
            return tracked;
        }
    }

    private final class TrackedInputStream extends FilterInputStream {
        private boolean streamClosed;

        private TrackedInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            requireOpen();
            return super.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            requireOpen();
            return super.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            synchronized (lifecycle) {
                if (streamClosed) {
                    return;
                }
                streamClosed = true;
                openStreams.remove(this);
            }
            super.close();
        }

        private void closeFromOwner() throws IOException {
            synchronized (lifecycle) {
                if (streamClosed) {
                    return;
                }
                streamClosed = true;
            }
            super.close();
        }

        private void requireOpen() throws IOException {
            synchronized (lifecycle) {
                if (streamClosed || closed) {
                    throw new IOException("Request body stream is closed");
                }
            }
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        private LimitedInputStream(InputStream input, long remaining) {
            super(input);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            var value = super.read();
            if (value < 0) {
                throw new IOException("Request body ended before its declared size");
            }
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (remaining == 0) {
                return -1;
            }
            var read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read < 0) {
                throw new IOException("Request body ended before its declared size");
            }
            remaining -= read;
            return read;
        }
    }
}
