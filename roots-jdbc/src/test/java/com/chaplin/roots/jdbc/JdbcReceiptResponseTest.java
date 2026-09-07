package com.chaplin.roots.jdbc;

import com.chaplin.roots.Response;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class JdbcReceiptResponseTest {
    @Test
    void preservesBinaryBodiesAndRepeatedHeadersAndRejectsUnstoreableResponses() throws Exception {
        var body = new byte[1024];
        new java.util.Random(123).nextBytes(body);
        var response = new Response(200, Map.of("X-Multi", List.of("one", "two")), body);
        var encoded = JdbcReceiptResponse.encode(response, 1024);
        var decoded = JdbcReceiptResponse.decode(200, encoded.headers(), encoded.body(), 1024);
        assertArrayEquals(body, decoded.body());
        assertEquals(response.headers(), decoded.headers());
        assertThrows(IOException.class, () -> JdbcReceiptResponse.decode(200, encoded.headers(), encoded.body(), 1));
        assertThrows(IllegalArgumentException.class, () -> JdbcReceiptResponse.encode(new Response(100, Map.of(), new byte[0]), 1024));
        assertThrows(IllegalArgumentException.class, () -> JdbcReceiptResponse.encode(Response.stream(200, "text/plain",
                output -> { throw new AssertionError("Stream writer must never be invoked"); }), 1024));
        var headers = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < 65; i++) headers.put("X-" + i, List.of("v"));
        assertThrows(IllegalArgumentException.class, () -> JdbcReceiptResponse.encode(new Response(200, headers, body), 1024));
        for (var values : List.of(java.util.Collections.nCopies(129, "a"), List.of("a".repeat(16384)),
                List.of("漢".repeat(9000)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> JdbcReceiptResponse.encode(new Response(200, Map.of("X-Header", values), body), 1024));
        }
    }

    @Test
    void rejectsCorruptVersionsCountsDuplicatesAndTrailingData() throws Exception {
        for (var pair : new int[][]{{2, 0}, {1, -1}, {1, 65}}) {
            var header = Base64.getEncoder().encodeToString(ByteBuffer.allocate(8).putInt(pair[0]).putInt(pair[1]).array());
            assertThrows(IOException.class, () -> JdbcReceiptResponse.decode(200, header, "", 1024));
        }
        for (int count : new int[]{-1, 129}) {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(1); out.writeInt(1); out.writeUTF("X-Test"); out.writeInt(count);
            }
            var header = Base64.getEncoder().encodeToString(bytes.toByteArray());
            assertThrows(IOException.class, () -> JdbcReceiptResponse.decode(200, header, "", 1024));
        }
        var duplicates = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(duplicates)) {
            out.writeInt(1); out.writeInt(2);
            for (int i = 0; i < 2; i++) { out.writeUTF("X-Test"); out.writeInt(0); }
        }
        assertThrows(IOException.class, () -> JdbcReceiptResponse.decode(200,
                Base64.getEncoder().encodeToString(duplicates.toByteArray()), "", 1024));
        var valid = JdbcReceiptResponse.encode(Response.text(200, "ok"), 1024);
        byte[] bytes = Base64.getDecoder().decode(valid.headers());
        var trailing = Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(bytes, bytes.length + 1));
        assertThrows(IOException.class, () -> JdbcReceiptResponse.decode(200, trailing, valid.body(), 1024));
        assertThrows(IOException.class, () -> JdbcReceiptResponse.decode(200, valid.headers(), "!", 1024));
        assertThrows(IOException.class, () -> JdbcReceiptResponse.decode(200, null, "", 1024));
        assertThrows(IOException.class, () -> JdbcReceiptResponse.decode(200, "x".repeat(32769), "", 1024));
        assertThrows(IOException.class, () -> JdbcReceiptResponse.decode(100, valid.headers(), valid.body(), 1024));
    }
}
