package com.chaplin.roots.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SpoolingBodyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsSmallBodiesInMemoryAndProvidesExactRepeatableSlices() throws Exception {
        var source = "0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var body = SpoolingBody.read(new ByteArrayInputStream(source), 10, 10, temporaryDirectory);

        assertTrue(body.inMemory());
        assertEquals(10, body.size());
        assertArrayEquals(source, body.readAllBytes());
        assertArrayEquals("3456".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                body.slice(3, 4).readAllBytes());
        assertThrows(IndexOutOfBoundsException.class, () -> body.slice(-1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> body.slice(9, 2));

        body.close();
        assertThrows(IOException.class, body::openStream);
    }

    @Test
    void spillsLargeBodiesAndDeletesTheBackingFileDeterministically() throws Exception {
        var source = new byte[150_000];
        for (var index = 0; index < source.length; index++) {
            source[index] = (byte) (index * 31);
        }
        var body = SpoolingBody.read(new ByteArrayInputStream(source), source.length, 1_024, temporaryDirectory);
        var path = body.temporaryFile();

        assertFalse(body.inMemory());
        assertTrue(Files.isRegularFile(path));
        assertEquals(source.length, Files.size(path));
        assertArrayEquals(Arrays.copyOfRange(source, 65_000, 90_000), body.slice(65_000, 25_000).readAllBytes());
        assertArrayEquals(source, body.readAllBytes());
        var forgottenStream = body.slice(1, 8).openStream();

        body.close();
        body.close();
        assertFalse(Files.exists(path));
        assertThrows(IOException.class, body::openStream);
        assertThrows(IOException.class, forgottenStream::read);
    }

    @Test
    void removesPartialFilesWhenTheLimitOrInputFails() throws Exception {
        assertThrows(HttpSupport.RequestTooLargeException.class, () -> SpoolingBody.read(
                new ByteArrayInputStream(new byte[2_048]), 1_500, 128, temporaryDirectory
        ));
        assertDirectoryEmpty();

        var failing = new InputStream() {
            private int count;

            @Override
            public int read() throws IOException {
                if (count++ == 512) {
                    throw new IOException("expected read failure");
                }
                return 1;
            }
        };
        assertThrows(IOException.class, () -> SpoolingBody.read(failing, 2_048, 128, temporaryDirectory));
        assertDirectoryEmpty();
    }

    @Test
    void rejectsInvalidSpoolLimits() {
        assertThrows(IllegalArgumentException.class, () -> SpoolingBody.read(
                InputStream.nullInputStream(), -1, 0, temporaryDirectory
        ));
        assertThrows(IllegalArgumentException.class, () -> SpoolingBody.read(
                InputStream.nullInputStream(), 10, 11, temporaryDirectory
        ));
    }

    @Test
    void defaultDirectoryZeroReadsSingleByteReadsAndEarlyStreamCloseAreSafe() throws Exception {
        var input = new InputStream() {
            private final byte[] values = {4, 5, 6};
            private int position = -1;

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (position++ < 0) {
                    return 0;
                }
                if (position > values.length) {
                    return -1;
                }
                buffer[offset] = values[position - 1];
                return 1;
            }

            @Override
            public int read() {
                throw new AssertionError("bulk read expected");
            }
        };
        var body = SpoolingBody.read(input, 3, 0);
        var path = body.temporaryFile();
        try {
            assertFalse(body.inMemory());
            var slice = body.slice(0, 3);
            try (var stream = slice.openStream()) {
                assertEquals(4, stream.read());
                assertArrayEquals(new byte[]{5, 6}, stream.readAllBytes());
                assertEquals(-1, stream.read());
                stream.close();
            }
            slice.close();
        } finally {
            body.close();
        }
        assertFalse(Files.exists(path));
    }

    @Test
    void multipartTextLimitDoesNotForceFileContentIntoMemory() throws Exception {
        var boundary = "body-policy-boundary";
        var bodyBytes = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"description\"\r\n\r\n"
                + "too-long\r\n"
                + "--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var body = SpoolingBody.read(new ByteArrayInputStream(bodyBytes), bodyBytes.length, 8, temporaryDirectory);
        try {
            var failure = assertThrows(HttpSupport.BadRequestException.class,
                    () -> HttpSupport.multipart("multipart/form-data; boundary=" + boundary, body, 4));
            assertEquals("Multipart text field is too large", failure.getMessage());
        } finally {
            body.close();
        }
        assertDirectoryEmpty();
    }

    private void assertDirectoryEmpty() throws IOException {
        try (var entries = Files.list(temporaryDirectory)) {
            assertEquals(0, entries.count());
        }
    }
}
