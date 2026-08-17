package dev.roots;

import dev.roots.validation.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

final class HttpTypesTest {
    @Test
    void requestDefensivelyCopiesNestedCollectionsAndBody() {
        var queryValues = new ArrayList<>(List.of("first"));
        var query = new LinkedHashMap<String, List<String>>();
        query.put("q", queryValues);
        var body = "payload".getBytes(StandardCharsets.UTF_8);
        var request = new Request(
                "post",
                "/items",
                Map.of(),
                query,
                Map.of("X-Test", List.of("yes")),
                Map.of("name", List.of("Ada")),
                body,
                new Session("session")
        );

        queryValues.add("mutated");
        body[0] = 'X';
        var returnedBody = request.body();
        returnedBody[0] = 'Y';

        assertEquals("POST", request.method());
        assertEquals("/items", request.transportPath());
        assertEquals(List.of("first"), request.query().get("q"));
        assertEquals("yes", request.header("x-test").orElseThrow());
        assertEquals("payload", request.bodyText());
        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), request.body());
        assertThrows(UnsupportedOperationException.class, () -> request.query().get("q").add("no"));

        var routed = request.withParameters(Map.of("itemId", "42"));
        assertEquals("42", routed.parameters().get("itemId"));
        assertEquals("Ada", routed.formValue("name").orElseThrow());
        var logical = request.withLogicalPath("/logical");
        assertEquals("/logical", logical.path());
        assertEquals("/items", logical.transportPath());
    }

    @Test
    void responseIsImmutableAndRejectsHeaderInjection() {
        var values = new ArrayList<>(List.of("one"));
        var body = "ok".getBytes(StandardCharsets.UTF_8);
        var response = new Response(200, Map.of("X-Test", values), body);

        values.add("two");
        body[0] = 'X';
        var returnedBody = response.body();
        returnedBody[0] = 'Y';
        var replaced = response.withHeader("x-test", "replacement");

        assertEquals(List.of("one"), response.headers().get("X-Test"));
        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), response.body());
        assertEquals(1, replaced.headers().size());
        assertEquals(List.of("replacement"), replaced.headers().get("x-test"));
        assertThrows(IllegalArgumentException.class, () -> response.withHeader("X-Test\r\nInjected", "bad"));
        assertThrows(IllegalArgumentException.class, () -> response.withHeader("X-Test", "bad\r\nInjected: yes"));
        assertThrows(IllegalArgumentException.class, () -> new Response(99, Map.of(), new byte[0]));
        assertEquals(response, new Response(200, Map.of("X-Test", List.of("one")),
                "ok".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void streamingResponsesAreLazyBoundedAndPreservedByHeaderCopies() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var response = Response.stream(200, "application/octet-stream", 5, output -> {
            calls.incrementAndGet();
            output.write("hello".getBytes(StandardCharsets.UTF_8));
        }).withHeader("X-Stream", "yes");

        assertTrue(response.streaming());
        assertEquals(5, response.contentLength().orElseThrow());
        assertEquals(0, calls.get());
        assertEquals("yes", response.headers().get("X-Stream").getFirst());
        assertThrows(IllegalStateException.class, response::body);
        assertThrows(IllegalStateException.class, response::bodyText);

        var output = new ByteArrayOutputStream();
        response.transferTo(output);
        assertEquals("hello", output.toString(StandardCharsets.UTF_8));
        assertEquals(1, calls.get());

        var chunked = Response.stream(200, "text/plain", stream -> {
            stream.write("one".getBytes(StandardCharsets.UTF_8));
            stream.close();
            stream.write("-two".getBytes(StandardCharsets.UTF_8));
        });
        assertTrue(chunked.contentLength().isEmpty());
        output.reset();
        chunked.transferTo(output);
        assertEquals("one-two", output.toString(StandardCharsets.UTF_8));

        assertThrows(IOException.class,
                () -> Response.stream(200, "text/plain", 3, stream -> stream.write("no".getBytes()))
                        .transferTo(new ByteArrayOutputStream()));
        assertThrows(IOException.class,
                () -> Response.stream(200, "text/plain", 2, stream -> stream.write("long".getBytes()))
                        .transferTo(new ByteArrayOutputStream()));
        assertThrows(IllegalArgumentException.class,
                () -> Response.stream(200, "text/plain", -1, stream -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> Response.stream(204, "text/plain", stream -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> Response.stream(205, "text/plain", stream -> { }));
        assertThrows(NullPointerException.class,
                () -> Response.stream(200, "text/plain", (ResponseBodyWriter) null));
        assertThrows(IllegalArgumentException.class,
                () -> response.withHeader("Content-Length", "4"));
        assertEquals("5", response.withHeader("Content-Length", "5")
                .headers().get("Content-Length").getFirst());
        assertThrows(IllegalArgumentException.class,
                () -> chunked.withHeader("Content-Length", "7"));
    }

    @Test
    void fileResponsesOpenContentOnlyWhenTransferred(@TempDir Path directory) throws Exception {
        var file = directory.resolve("export.csv");
        Files.writeString(file, "id,name\n1,Ada\n", StandardCharsets.UTF_8);
        var response = Response.file(200, "text/csv; charset=utf-8", file);

        assertTrue(response.streaming());
        assertEquals(Files.size(file), response.contentLength().orElseThrow());
        var output = new ByteArrayOutputStream();
        response.transferTo(output);
        assertEquals(Files.readString(file), output.toString(StandardCharsets.UTF_8));
        assertThrows(java.nio.file.NoSuchFileException.class,
                () -> Response.file(200, "text/plain", directory.resolve("missing")));
        assertFalse(response.toString().contains("id,name"));
    }

    @Test
    void uploadedFilesRemainBinarySafeAndImmutableAcrossRequestsAndActions() {
        var source = new byte[]{0, 1, (byte) 0xff};
        var uploaded = new UploadedFile("../evidence.bin", "application/octet-stream", source);
        source[0] = 9;
        var returned = uploaded.content();
        returned[1] = 9;

        var fileList = new ArrayList<>(List.of(uploaded));
        var cache = RootsCache.inMemory(3);
        var request = new Request(
                "POST",
                "/upload",
                "/upload",
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("title", List.of("Evidence")),
                Map.of("attachment", fileList),
                new byte[]{4, 5},
                new Session("upload-request"),
                cache
        );
        fileList.clear();

        assertEquals(3, uploaded.size());
        assertArrayEquals(new byte[]{0, 1, (byte) 0xff}, uploaded.content());
        assertEquals("../evidence.bin", request.file("attachment").orElseThrow().filename());
        assertEquals(List.of(uploaded), request.files("attachment"));
        assertEquals(List.of("Evidence"), request.formValues("title"));
        assertEquals(Map.of("title", List.of("Evidence")), request.allFormValues());
        assertEquals(Map.of("attachment", List.of(uploaded)), request.allFiles());
        var bound = request.bind(RequestForm.class);
        assertEquals("Evidence", bound.title());
        assertEquals(uploaded, bound.attachment());
        assertTrue(request.file("missing").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> request.files().put("no", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> request.files("attachment").add(uploaded));
        assertSame(cache, request.cache());
        assertSame(cache, request.withLogicalPath("/logical").cache());

        var event = new ActionEvent(
                "submit",
                Map.of("title", List.of("Evidence")),
                Map.of("attachment", List.of(uploaded)),
                request.session(),
                cache
        );
        assertEquals(uploaded, event.file("attachment").orElseThrow());
        assertEquals(List.of(uploaded), event.files("attachment"));
        assertTrue(event.file("missing").isEmpty());
        assertSame(cache, event.cache());
        assertThrows(UnsupportedOperationException.class, () -> event.allFiles().put("no", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new UploadedFile("bad\r\nname", "text/plain", new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new UploadedFile("name", " ", new byte[0]));
    }

    @Test
    void uploadedFilesExposeRepeatableStreamsAndTransferToApplicationStorage() throws Exception {
        var uploaded = new UploadedFile("evidence.txt", "text/plain", "repeatable".getBytes(StandardCharsets.UTF_8));
        var destination = Files.createTempFile("roots-uploaded-file-test-", ".txt");
        try {
            assertTrue(uploaded.inMemory());
            try (var first = uploaded.openStream(); var second = uploaded.openStream()) {
                assertArrayEquals(first.readAllBytes(), second.readAllBytes());
            }
            assertEquals(destination.toAbsolutePath().normalize(), uploaded.transferTo(destination));
            assertEquals("repeatable", Files.readString(destination));
        } finally {
            Files.deleteIfExists(destination);
        }
    }

    @Test
    void configValidatesTimeoutsAndFreezesMiddleware() {
        Middleware middleware = (request, chain) -> chain.next();
        var config = RootsConfig.forApplication(HttpTypesTest.class)
                .port(0)
                .viewTimeout(Duration.ofSeconds(5))
                .sessionTimeout(Duration.ofMinutes(1))
                .secureCookies(true)
                .use(middleware)
                .build();

        assertEquals(List.of(middleware), config.middleware());
        assertTrue(config.secureCookies());
        assertEquals(RootsConfig.DEFAULT_MAX_REQUEST_BYTES, config.maxRequestBytes());
        assertThrows(UnsupportedOperationException.class, () -> config.middleware().add(middleware));
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(HttpTypesTest.class)
                .viewTimeout(Duration.ZERO)
                .build());
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(HttpTypesTest.class)
                .sessionTimeout(Duration.ofSeconds(-1))
                .build());
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(HttpTypesTest.class)
                .maxRequestBytes(0)
                .build());
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(HttpTypesTest.class)
                .maxRequestBytes(Integer.MAX_VALUE)
                .build());
        assertThrows(IllegalArgumentException.class, () -> RootsConfig.forApplication(HttpTypesTest.class)
                .arguments("--unknown")
                .build());
        assertTrue(config.development());
    }

    @Test
    void requestBodyPolicyIsValidatedAndConfigurable() throws Exception {
        var directory = Files.createTempDirectory("roots-body-policy-test-");
        try {
            var policy = new RequestBodyPolicy(1_024, 8_192, directory);
            var config = RootsConfig.forApplication(HttpTypesTest.class)
                    .requestBodyPolicy(policy)
                    .requestBodyMemoryThreshold(2_048)
                    .maxMultipartTextFieldBytes(16_384)
                    .requestBodyTemporaryDirectory(directory)
                    .build();

            assertEquals(2_048, config.requestBodyPolicy().memoryThreshold());
            assertEquals(16_384, config.requestBodyPolicy().maxMultipartTextFieldBytes());
            assertEquals(directory.toAbsolutePath().normalize(),
                    config.requestBodyPolicy().temporaryDirectory());
            assertThrows(IllegalArgumentException.class,
                    () -> new RequestBodyPolicy(-1, 1, directory));
            assertThrows(IllegalArgumentException.class,
                    () -> new RequestBodyPolicy(0, 0, directory));
            assertThrows(IllegalArgumentException.class,
                    () -> new RequestBodyPolicy(0, 1, directory.resolve("missing")));
        } finally {
            Files.deleteIfExists(directory);
        }
    }

    private record RequestForm(@NotBlank String title, UploadedFile attachment) {
    }
}
