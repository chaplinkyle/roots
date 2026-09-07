package com.chaplin.roots.internal;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ImageOptimizerTest {
    @Test
    void resizesPngWithoutUpscalingAndCachesProductionResults() throws Exception {
        var loads = new AtomicInteger();
        var source = png(8, 4, true);
        var optimizer = new ImageOptimizer(loader(Map.of("public/images/a.png", source), loads), false);

        var resized = optimizer.optimize(query("/images/a.png", "4", "82"));
        var cached = optimizer.optimize(query("/images/a.png", "4", "82"));
        var bounded = optimizer.optimize(query("/images/a.png", "16", "82"));

        assertEquals("image/png", resized.contentType());
        assertArrayEquals(resized.body(), cached.body());
        assertEquals(2, loads.get());
        try (var input = new ByteArrayInputStream(resized.body())) {
            var decoded = ImageIO.read(input);
            assertEquals(4, decoded.getWidth());
            assertEquals(2, decoded.getHeight());
            assertTrue(decoded.getColorModel().hasAlpha());
        }
        try (var input = new ByteArrayInputStream(bounded.body())) {
            assertEquals(8, ImageIO.read(input).getWidth());
        }
    }

    @Test
    void rechecksSourcesInDevelopmentAndEncodesJpegQuality() throws Exception {
        var loads = new AtomicInteger();
        var optimizer = new ImageOptimizer(loader(Map.of("public/photo.jpg", jpeg(10, 5)), loads), true);

        var low = optimizer.optimize(query("/photo.jpg", "5", "10"));
        var high = optimizer.optimize(query("/photo.jpg", "5", "95"));
        optimizer.optimize(query("/photo.jpg", "5", "10"));

        assertEquals("image/jpeg", low.contentType());
        assertTrue(high.body().length >= low.body().length);
        assertEquals(3, loads.get());
        assertEquals(5, ImageIO.read(new ByteArrayInputStream(high.body())).getWidth());
    }

    @Test
    void rejectsMalformedQueriesPathsFormatsAndDataWithPreciseStatuses() {
        var oversized = new byte[20 * 1024 * 1024 + 1];
        var optimizer = new ImageOptimizer(loader(Map.of(
                "public/bad.png", new byte[]{1, 2, 3},
                "public/large.png", oversized,
                "public/vector.svg", new byte[]{1}
        ), new AtomicInteger()), false);

        assertFailure(400, () -> optimizer.optimize(Map.of()));
        assertFailure(400, () -> optimizer.optimize(Map.of(
                "src", List.of("/bad.png"), "w", List.of("1"), "q", List.of("82"), "extra", List.of("x"))));
        assertFailure(400, () -> optimizer.optimize(query("../bad.png", "1", "82")));
        assertFailure(400, () -> optimizer.optimize(query("/bad.png", "0", "82")));
        assertFailure(400, () -> optimizer.optimize(query("/bad.png", "1", "101")));
        assertFailure(404, () -> optimizer.optimize(query("/missing.png", "1", "82")));
        assertFailure(415, () -> optimizer.optimize(query("/vector.svg", "1", "82")));
        assertFailure(415, () -> optimizer.optimize(query("/bad.png", "1", "82")));
        assertFailure(413, () -> optimizer.optimize(query("/large.png", "1", "82")));
    }

    private static void assertFailure(int status, ThrowingCall call) {
        var failure = assertThrows(ImageOptimizer.RequestFailure.class, call::run);
        assertEquals(status, failure.status());
    }

    private static Map<String, List<String>> query(String source, String width, String quality) {
        return Map.of("src", List.of(source), "w", List.of(width), "q", List.of(quality));
    }

    private static ClassLoader loader(Map<String, byte[]> resources, AtomicInteger loads) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                loads.incrementAndGet();
                var bytes = resources.get(name);
                return bytes == null ? null : new ByteArrayInputStream(bytes);
            }
        };
    }

    private static byte[] png(int width, int height, boolean alpha) throws Exception {
        var image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(20, 100, 180, alpha ? 128 : 255));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return output.toByteArray();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
