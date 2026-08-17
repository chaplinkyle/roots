package dev.roots;

import dev.roots.testapp.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AssetPipelineIntegrationTest {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private RunningApplication application;
    private Path imageFile;
    private Path fontFile;

    @BeforeEach
    void start() throws Exception {
        var classes = Path.of(Application.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        imageFile = classes.resolve("public/images/hero.png");
        fontFile = classes.resolve("public/fonts/roots.woff2");
        Files.createDirectories(imageFile.getParent());
        Files.createDirectories(fontFile.getParent());
        var image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(20, 80, 180, 160));
        graphics.fillRect(0, 0, 8, 4);
        graphics.dispose();
        ImageIO.write(image, "png", imageFile.toFile());
        Files.write(fontFile, new byte[]{'w', 'O', 'F', '2', 0, 0, 0, 0});
        InstanceFactory factory = type -> {
            try {
                return type.getDeclaredConstructor(String.class).newInstance("asset fixture");
            } catch (NoSuchMethodException ignored) {
                return type.getDeclaredConstructor().newInstance();
            }
        };
        application = Roots.start(RootsConfig.forApplication(Application.class)
                .port(0)
                .development(false)
                .instanceFactory(factory)
                .build());
    }

    @AfterEach
    void stop() throws Exception {
        if (application != null) {
            application.close();
        }
        Files.deleteIfExists(imageFile);
        Files.deleteIfExists(fontFile);
    }

    @Test
    void servesRenderedImagesFontCssAndFontFilesAsSessionFreeConditionalAssets() throws Exception {
        var page = get("/assets");
        assertEquals(200, page.statusCode());
        var html = new String(page.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.contains("src=\"/_roots/image?src=%2Fimages%2Fhero.png&amp;w=8&amp;q=70\""), html);
        assertTrue(html.contains("srcset=\"/_roots/image?src=%2Fimages%2Fhero.png&amp;w=4&amp;q=70 4w, "), html);
        assertTrue(html.contains("data-roots-font-preload"), html);
        assertTrue(html.contains("data-roots-framework-style href=\"/_roots/client.css\""), html);
        assertTrue(html.contains("/_roots/font.css?family=Roots+Sans&amp;src=%2Ffonts%2Froots.woff2"), html);

        var frameworkCss = get("/_roots/client.css");
        assertEquals(200, frameworkCss.statusCode());
        assertEquals("text/css; charset=utf-8",
                frameworkCss.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(new String(frameworkCss.body(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("[data-roots-announcer]"));
        assertTrue(frameworkCss.headers().firstValue("Set-Cookie").isEmpty());
        var frameworkEtag = frameworkCss.headers().firstValue("ETag").orElseThrow();
        assertEquals(304, send(HttpRequest.newBuilder(uri("/_roots/client.css"))
                .header("If-None-Match", frameworkEtag).GET().build()).statusCode());
        var frameworkHead = send(HttpRequest.newBuilder(uri("/_roots/client.css"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
        assertEquals(200, frameworkHead.statusCode());
        assertEquals(0, frameworkHead.body().length);
        assertEquals(frameworkEtag, frameworkHead.headers().firstValue("ETag").orElseThrow());
        var frameworkPost = send(HttpRequest.newBuilder(uri("/_roots/client.css"))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertEquals(405, frameworkPost.statusCode());
        assertEquals("GET, HEAD", frameworkPost.headers().firstValue("Allow").orElseThrow());
        assertTrue(frameworkPost.headers().firstValue("Set-Cookie").isEmpty());

        var image = get("/_roots/image?src=%2Fimages%2Fhero.png&w=4&q=70");
        assertEquals(200, image.statusCode());
        assertEquals("image/png", image.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("public, max-age=86400",
                image.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(image.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(4, ImageIO.read(new java.io.ByteArrayInputStream(image.body())).getWidth());
        var etag = image.headers().firstValue("ETag").orElseThrow();
        var unchanged = send(HttpRequest.newBuilder(uri("/_roots/image?src=%2Fimages%2Fhero.png&w=4&q=70"))
                .header("If-None-Match", etag).GET().build());
        assertEquals(304, unchanged.statusCode());
        assertEquals(0, unchanged.body().length);

        var css = get("/_roots/font.css?family=Roots+Sans&src=%2Ffonts%2Froots.woff2&weight=400&style=normal&display=swap");
        assertEquals(200, css.statusCode());
        assertEquals("text/css; charset=utf-8", css.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(new String(css.body(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("src:url(\"../fonts/roots.woff2\") format(\"woff2\")"));
        assertTrue(css.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(200, get("/fonts/roots.woff2").statusCode());
    }

    @Test
    void rejectsInvalidAssetRequestsWithoutCreatingSessions() throws Exception {
        var malformed = get("/_roots/image?src=%2Fimages%2Fhero.png&w=0&q=70");
        assertEquals(400, malformed.statusCode());
        assertTrue(malformed.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(404, get("/_roots/image?src=%2Fmissing.png&w=4&q=70").statusCode());
        assertEquals(415, get("/_roots/image?src=%2Ffonts%2Froots.woff2&w=4&q=70").statusCode());
        assertEquals(400, get("/_roots/font.css?family=Bad&src=%2Fbad.css&weight=400&style=normal&display=swap").statusCode());
        assertEquals(404, get("/_roots/font.css?family=Missing&src=%2Fmissing.woff2&weight=400&style=normal&display=swap").statusCode());

        var post = send(HttpRequest.newBuilder(uri("/_roots/image?src=%2Fimages%2Fhero.png&w=4&q=70"))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertEquals(405, post.statusCode());
        assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElseThrow());
        assertFalse(post.body().length == 0);
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return application.uri().resolve(path);
    }
}
