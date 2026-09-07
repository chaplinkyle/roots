package com.chaplin.roots.internal;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Pure-JDK local raster-image transformation with bounded production caching. */
final class ImageOptimizer {
    private static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;
    private static final long MAX_SOURCE_PIXELS = 40_000_000L;
    private static final int MAX_OUTPUT_WIDTH = 4096;
    private static final int MAX_CACHE_ENTRIES = 256;

    private final ClassLoader loader;
    private final boolean development;
    private final Map<Key, Asset> cache = new LinkedHashMap<>(32, 0.75f, true);

    ImageOptimizer(ClassLoader loader, boolean development) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.development = development;
    }

    Asset optimize(Map<String, List<String>> query) throws IOException {
        if (!query.keySet().equals(java.util.Set.of("src", "w", "q"))) {
            throw new RequestFailure(400, "Image requests require only src, w, and q parameters");
        }
        var key = new Key(
                one(query, "src"),
                integer(one(query, "w"), "w", 1, MAX_OUTPUT_WIDTH),
                integer(one(query, "q"), "q", 1, 100)
        );
        validateSource(key.source());
        if (development) {
            return transform(key);
        }
        synchronized (cache) {
            var cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            var transformed = transform(key);
            cache.put(key, transformed);
            while (cache.size() > MAX_CACHE_ENTRIES) {
                cache.remove(cache.keySet().iterator().next());
            }
            return transformed;
        }
    }

    private Asset transform(Key key) throws IOException {
        var sourceBytes = resource(key.source());
        try (var bytes = new ByteArrayInputStream(sourceBytes);
             ImageInputStream imageInput = ImageIO.createImageInputStream(bytes)) {
            if (imageInput == null) {
                throw new RequestFailure(415, "Unsupported image data");
            }
            var readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new RequestFailure(415, "Unsupported image data");
            }
            var reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                var format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(format.equals("png") || format.equals("jpeg") || format.equals("jpg"))) {
                    throw new RequestFailure(415, "Only PNG and JPEG images can be optimized");
                }
                var sourceWidth = reader.getWidth(0);
                var sourceHeight = reader.getHeight(0);
                if (sourceWidth < 1 || sourceHeight < 1
                        || (long) sourceWidth * sourceHeight > MAX_SOURCE_PIXELS) {
                    throw new RequestFailure(413, "Image dimensions exceed the optimizer limit");
                }
                var source = reader.read(0);
                var outputWidth = Math.min(key.width(), sourceWidth);
                var outputHeight = Math.max(1, (int) Math.round((double) sourceHeight * outputWidth / sourceWidth));
                if (outputHeight > MAX_OUTPUT_WIDTH) {
                    throw new RequestFailure(413, "Optimized image dimensions exceed the output limit");
                }
                var png = format.equals("png");
                var type = png ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
                var resized = new BufferedImage(outputWidth, outputHeight, type);
                var graphics = resized.createGraphics();
                try {
                    graphics.setComposite(AlphaComposite.Src);
                    if (!png) {
                        graphics.setColor(Color.WHITE);
                        graphics.fillRect(0, 0, outputWidth, outputHeight);
                    }
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    graphics.drawImage(source, 0, 0, outputWidth, outputHeight, null);
                } finally {
                    graphics.dispose();
                }
                return encode(resized, png, key.quality());
            } finally {
                reader.dispose();
            }
        } catch (RequestFailure failure) {
            throw failure;
        } catch (IOException failure) {
            throw new RequestFailure(415, "Malformed image data", failure);
        } catch (RuntimeException failure) {
            throw new RequestFailure(415, "Malformed image data", failure);
        }
    }

    private static Asset encode(BufferedImage image, boolean png, int quality) throws IOException {
        var output = new ByteArrayOutputStream();
        var format = png ? "png" : "jpeg";
        var writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            throw new IllegalStateException("The Java runtime has no " + format + " image writer");
        }
        var writer = writers.next();
        try (var imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            var parameters = writer.getDefaultWriteParam();
            if (!png && parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(quality / 100.0f);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return new Asset(png ? "image/png" : "image/jpeg", output.toByteArray());
    }

    private byte[] resource(String source) throws IOException {
        try (var input = loader.getResourceAsStream("public" + source)) {
            if (input == null) {
                throw new RequestFailure(404, "Image asset not found");
            }
            var bytes = input.readNBytes(MAX_SOURCE_BYTES + 1);
            if (bytes.length > MAX_SOURCE_BYTES) {
                throw new RequestFailure(413, "Image source exceeds the optimizer limit");
            }
            return bytes;
        }
    }

    private static String one(Map<String, List<String>> query, String name) {
        var values = query.get(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new RequestFailure(400, "Image parameter '" + name + "' must appear exactly once");
        }
        return values.getFirst();
    }

    private static int integer(String value, String name, int minimum, int maximum) {
        try {
            var parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new RequestFailure(400, "Image parameter '" + name + "' must be between "
                    + minimum + " and " + maximum);
        }
    }

    private static void validateSource(String source) {
        if (!RootsServer.validAssetPath(source) || source.indexOf('?') >= 0 || source.indexOf('#') >= 0) {
            throw new RequestFailure(400, "Image source must be a root-relative public asset path");
        }
        var extension = RootsServer.extension(source);
        if (!(extension.equals("png") || extension.equals("jpg") || extension.equals("jpeg"))) {
            throw new RequestFailure(415, "Only PNG and JPEG images can be optimized");
        }
    }

    record Asset(String contentType, byte[] body) {
        Asset {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    static final class RequestFailure extends IllegalArgumentException {
        private final int status;

        RequestFailure(int status, String message) {
            super(message);
            this.status = status;
        }

        RequestFailure(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private record Key(String source, int width, int quality) {
    }
}
