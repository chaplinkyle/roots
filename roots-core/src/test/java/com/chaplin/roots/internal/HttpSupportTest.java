package com.chaplin.roots.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HttpSupportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesRepeatedUrlEncodedValuesAndRejectsMalformedEscapes() {
        assertEquals(
                Map.of("name", List.of("Ada Lovelace"), "role", List.of("admin", "reviewer")),
                HttpSupport.parameters("name=Ada+Lovelace&role=admin&role=reviewer")
        );
        var failure = assertThrows(
                HttpSupport.BadRequestException.class,
                () -> HttpSupport.parameters("name=%not-hex")
        );
        assertEquals("Malformed URL-encoded data", failure.getMessage());
    }

    @Test
    void serializesJsonWithoutEmbeddingExecutableMarkup() {
        assertEquals(
                "\"\\u003c/script\\u003e\\u0026\\\"\\n\"",
                HttpSupport.jsonString("</script>&\"\n")
        );
        assertEquals("null", HttpSupport.jsonString(null));
    }

    @Test
    void parsesMultipartTextAndRepeatedBinaryFilesWithoutFalseBoundaries() {
        var boundary = "RootsBoundary7MA4YWxk";
        var binary = concat(
                new byte[]{0, 1, 2, (byte) 0xff},
                ("inside\r\n--" + boundary + "Xnot-a-boundary\r\nend").getBytes(StandardCharsets.UTF_8)
        );
        var body = multipart(boundary,
                part("Content-Disposition: form-data; name=\"title\"\r\n"
                        + "Content-Type: text/plain; charset=UTF-8", "Résumé".getBytes(StandardCharsets.UTF_8)),
                part("Content-Disposition: form-data; name=\"attachments\"; filename=\"../../résumé;a.bin\"\r\n"
                        + "Content-Type: application/octet-stream", binary),
                part("Content-Disposition: form-data; name=\"attachments\"; filename=\"empty.txt\"\r\n"
                        + "Content-Type: text/plain", new byte[0]),
                part("Content-Disposition: form-data; name*=UTF-8''attachments; "
                        + "filename*=UTF-8''r%C3%A9sum%C3%A9.txt\r\n"
                        + "Content-Type: text/plain", "international".getBytes(StandardCharsets.UTF_8))
        );

        var parsed = HttpSupport.multipart("multipart/form-data; boundary=\"" + boundary + "\"", body);

        assertEquals(List.of("Résumé"), parsed.values().get("title"));
        assertEquals(3, parsed.files().get("attachments").size());
        var first = parsed.files().get("attachments").getFirst();
        assertEquals("../../résumé;a.bin", first.filename());
        assertEquals("application/octet-stream", first.contentType());
        assertArrayEquals(binary, first.content());
        assertEquals(0, parsed.files().get("attachments").get(1).size());
        assertEquals("résumé.txt", parsed.files().get("attachments").getLast().filename());
        assertEquals("international", parsed.files().get("attachments").getLast().contentText());
        assertThrows(UnsupportedOperationException.class,
                () -> parsed.files().get("attachments").add(first));
    }

    @Test
    void rejectsMalformedMultipartFramingHeadersCharsetsAndLimits() {
        var missingBoundary = assertThrows(HttpSupport.BadRequestException.class,
                () -> HttpSupport.multipart("multipart/form-data", new byte[0]));
        assertTrue(missingBoundary.getMessage().contains("boundary"));

        assertThrows(HttpSupport.BadRequestException.class, () -> HttpSupport.multipart(
                "multipart/form-data; boundary=bad boundary",
                "--bad boundary--".getBytes(StandardCharsets.UTF_8)
        ));
        assertThrows(HttpSupport.BadRequestException.class, () -> HttpSupport.multipart(
                "multipart/form-data; boundary=x",
                "--x\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue"
                        .getBytes(StandardCharsets.UTF_8)
        ));
        assertThrows(HttpSupport.BadRequestException.class, () -> HttpSupport.multipart(
                "multipart/form-data; boundary=x",
                multipart("x", part("Content-Type: text/plain", "value".getBytes(StandardCharsets.UTF_8)))
        ));
        assertThrows(HttpSupport.BadRequestException.class, () -> HttpSupport.multipart(
                "multipart/form-data; boundary=x",
                multipart("x", part("Content-Disposition: form-data; name=\"field\"\r\n"
                        + "Content-Disposition: form-data; name=\"again\"", new byte[0]))
        ));
        assertThrows(HttpSupport.BadRequestException.class, () -> HttpSupport.multipart(
                "multipart/form-data; boundary=x",
                multipart("x", part("Content-Disposition: form-data; name=\"field\"\r\n"
                        + "Content-Type: text/plain; charset=not-a-charset", new byte[0]))
        ));
        assertThrows(HttpSupport.BadRequestException.class, () -> HttpSupport.multipart(
                "multipart/form-data; boundary=x",
                multipart("x", part("Content-Disposition: form-data; name=\"file\"; "
                        + "filename*=UTF-8''bad%ZZname.txt", new byte[0]))
        ));
        assertThrows(HttpSupport.BadRequestException.class, () -> HttpSupport.multipart(
                "multipart/form-data; boundary=x",
                multipart("x", part("Content-Disposition: form-data; name=\"file\"; "
                        + "filename*=UTF-8''bad%0Aname.txt", new byte[0]))
        ));

        var tooMany = new Part[257];
        for (var index = 0; index < tooMany.length; index++) {
            tooMany[index] = part("Content-Disposition: form-data; name=\"field\"", new byte[0]);
        }
        var failure = assertThrows(HttpSupport.BadRequestException.class,
                () -> HttpSupport.multipart("multipart/form-data; boundary=x", multipart("x", tooMany)));
        assertTrue(failure.getMessage().contains("too many parts"));
    }

    @Test
    void diskBackedParserMatchesTheLegacyBinaryContractAcrossThresholds() throws Exception {
        var boundary = "RootsParityBoundary";
        var binary = new byte[180_000];
        for (var index = 0; index < binary.length; index++) {
            binary[index] = (byte) (index * 29);
        }
        var falseMarker = ("\r\n--" + boundary + "_still-content")
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(falseMarker, 0, binary, 65_520, falseMarker.length);
        var encoded = multipart(boundary,
                part("Content-Disposition: form-data; name=\"title\"", "parity".getBytes(StandardCharsets.UTF_8)),
                part("Content-Disposition: form-data; name=\"file\"; filename=\"parity.bin\"\r\n"
                        + "Content-Type: application/octet-stream", binary));
        var expected = HttpSupport.multipart("multipart/form-data; boundary=" + boundary, encoded);

        for (var threshold : List.of(0, 17, 65_536, encoded.length)) {
            var body = SpoolingBody.read(
                    new ByteArrayInputStream(encoded), encoded.length, threshold, temporaryDirectory
            );
            try {
                var actual = HttpSupport.multipart(
                        "multipart/form-data; boundary=" + boundary, body, 1_024
                );
                assertEquals(expected.values(), actual.values());
                var expectedFile = expected.files().get("file").getFirst();
                var actualFile = actual.files().get("file").getFirst();
                assertEquals(expectedFile.filename(), actualFile.filename());
                assertEquals(expectedFile.contentType(), actualFile.contentType());
                assertEquals(expectedFile.size(), actualFile.size());
                assertArrayEquals(expectedFile.content(), actualFile.content());
                assertEquals(threshold >= encoded.length, actualFile.inMemory());
            } finally {
                body.close();
            }
        }
        try (var entries = Files.list(temporaryDirectory)) {
            assertEquals(0, entries.count());
        }
    }

    private static byte[] multipart(String boundary, Part... parts) {
        var output = new ByteArrayOutputStream();
        for (var part : parts) {
            output.writeBytes(("--" + boundary + "\r\n" + part.headers() + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.writeBytes(part.content());
            output.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    private static Part part(String headers, byte[] content) {
        return new Part(headers, content);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(first);
        output.writeBytes(second);
        return output.toByteArray();
    }

    private record Part(String headers, byte[] content) {
    }
}
