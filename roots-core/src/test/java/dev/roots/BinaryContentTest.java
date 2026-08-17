package dev.roots;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BinaryContentTest {
    @Test
    void inMemoryContentCopiesInputAndProducesRepeatableStreams() throws Exception {
        var source = new byte[]{1, 2, 3};
        var content = BinaryContent.of(source);
        source[0] = 9;

        assertTrue(content.inMemory());
        assertArrayEquals(new byte[]{1, 2, 3}, content.readAllBytes());
        try (var first = content.openStream(); var second = content.openStream()) {
            assertArrayEquals(first.readAllBytes(), second.readAllBytes());
        }
        content.close();
        assertThrows(NullPointerException.class, () -> BinaryContent.of(null));
    }

    @Test
    void readAllBytesRejectsImpossibleSizesTruncationAndStorageFailures() {
        assertThrows(IllegalStateException.class, () -> content(Integer.MAX_VALUE + 1L,
                InputStream.nullInputStream()).readAllBytes());
        assertThrows(UncheckedIOException.class, () -> content(2,
                new ByteArrayInputStream(new byte[]{1})).readAllBytes());
        assertThrows(UncheckedIOException.class, () -> new BinaryContent() {
            @Override
            public long size() {
                return 1;
            }

            @Override
            public InputStream openStream() throws IOException {
                throw new IOException("offline store");
            }

            @Override
            public boolean inMemory() {
                return false;
            }

            @Override
            public void close() {
            }
        }.readAllBytes());
    }

    private static BinaryContent content(long size, InputStream input) {
        return new BinaryContent() {
            @Override
            public long size() {
                return size;
            }

            @Override
            public InputStream openStream() {
                return input;
            }

            @Override
            public boolean inMemory() {
                return false;
            }

            @Override
            public void close() {
            }
        };
    }
}
