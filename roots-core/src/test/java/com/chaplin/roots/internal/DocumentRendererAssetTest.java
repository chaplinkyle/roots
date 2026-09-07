package com.chaplin.roots.internal;

import com.chaplin.roots.Metadata;
import com.chaplin.roots.HeadMetadata;
import com.chaplin.roots.OpenGraphMetadata;
import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.WebFont;
import com.chaplin.roots.testapp.Application;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class DocumentRendererAssetTest {
    @Test
    void emitsMountedFontStylesheetsAndPreloadsForLiveDocuments() {
        var metadata = new Metadata("Assets", "Fonts", List.of("/app.css"), List.of(
                WebFont.of("Roots Sans", "/fonts/roots.woff2").weight("100 900").preloaded()
        ));

        var html = DocumentRenderer.live(
                RootsConfig.forApplication(Application.class).build(),
                "view", "csrf", 1, "<main>content</main>", new ResolvedMetadata(metadata, new HeadMetadata()
                        .withCanonical("/assets")
                        .withRobots("noindex, nofollow")
                        .withThemeColor("#123456")
                        .withOpenGraph(new OpenGraphMetadata()
                                .withImage("/images/preview.png")
                                .withImageAlt("Preview & details")
                                .withType("website"))), "/company/roots"
        );

        assertTrue(html.contains("rel=\"preload\" data-roots-font-preload as=\"font\" type=\"font/woff2\" crossorigin href=\"/company/roots/fonts/roots.woff2\""), html);
        assertTrue(html.contains("rel=\"stylesheet\" data-roots-style href=\"/company/roots/app.css\""), html);
        assertTrue(html.contains("data-roots-framework-style href=\"/company/roots/_roots/client.css\""), html);
        assertTrue(html.contains("data-roots-announcer=\"polite\" role=\"status\""), html);
        assertTrue(html.contains("data-roots-announcer=\"assertive\" role=\"alert\""), html);
        assertTrue(html.contains("data-roots-font href=\"/company/roots/_roots/font.css?family=Roots+Sans&amp;src=%2Ffonts%2Froots.woff2"), html);
        assertTrue(html.contains("data-roots-head=\"canonical\" href=\"/company/roots/assets\""), html);
        assertTrue(html.contains("name=\"robots\" data-roots-head=\"robots\" content=\"noindex, nofollow\""), html);
        assertTrue(html.contains("name=\"theme-color\" data-roots-head=\"theme-color\" content=\"#123456\""), html);
        assertTrue(html.contains("property=\"og:title\" data-roots-head=\"og:title\" content=\"Assets\""), html);
        assertTrue(html.contains("property=\"og:description\" data-roots-head=\"og:description\" content=\"Fonts\""), html);
        assertTrue(html.contains("property=\"og:image\" data-roots-head=\"og:image\" content=\"/company/roots/images/preview.png\""), html);
        assertTrue(html.contains("content=\"Preview &amp; details\""), html);
    }
}
