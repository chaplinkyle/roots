package dev.roots.internal;

import dev.roots.HeadMetadata;
import dev.roots.Layout;
import dev.roots.OpenGraphMetadata;
import dev.roots.PageContext;
import dev.roots.Session;
import dev.roots.annotation.PageMetadata;
import dev.roots.html.Node;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static dev.roots.html.Html.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class MetadataSupportTest {
    @Test
    void overlaysNonEmptyAnnotationHeadValuesWithoutDiscardingDynamicValues() {
        var resolved = MetadataSupport.resolve(new MixedPage(), List.of(new OuterLayout(), new InnerLayout()),
                new PageContext("/dynamic", Map.of(), Map.of(), new Session("metadata")));

        assertEquals("Declared title", resolved.document().title());
        assertEquals("/declared", resolved.head().canonical());
        assertEquals("noindex, nofollow", resolved.head().robots());
        assertEquals("#224466", resolved.head().themeColor());
        assertEquals("Declared social title", resolved.head().openGraph().title());
        assertEquals("Dynamic social description", resolved.head().openGraph().description());
        assertEquals("/dynamic.png", resolved.head().openGraph().image());
        assertEquals("article", resolved.head().openGraph().type());
        assertEquals("/inner", resolved.head().openGraph().url());
        assertEquals("Roots Suite", resolved.head().openGraph().siteName());
    }

    @PageMetadata(
            title = "Declared title",
            canonical = "/declared",
            robots = "noindex, nofollow",
            openGraphTitle = "Declared social title"
    )
    private static final class MixedPage implements dev.roots.Page {
        @Override
        public HeadMetadata headMetadata(PageContext context) {
            return new HeadMetadata()
                    .withCanonical("/dynamic")
                    .withOpenGraph(new OpenGraphMetadata()
                            .withTitle("Dynamic social title")
                            .withDescription("Dynamic social description")
                            .withImage("/dynamic.png"));
        }

        @Override
        public Node render(PageContext context) {
            return text("metadata");
        }
    }

    private static final class OuterLayout implements Layout {
        @Override
        public HeadMetadata headMetadata(PageContext context) {
            return new HeadMetadata()
                    .withCanonical("/outer")
                    .withRobots("index, follow")
                    .withOpenGraph(new OpenGraphMetadata()
                            .withType("article")
                            .withSiteName("Roots Suite"));
        }

        @Override
        public Node render(PageContext context, Node children) {
            return children;
        }
    }

    private static final class InnerLayout implements Layout {
        @Override
        public HeadMetadata headMetadata(PageContext context) {
            return new HeadMetadata()
                    .withThemeColor("#224466")
                    .withOpenGraph(new OpenGraphMetadata()
                            .withDescription("Inner social description")
                            .withUrl("/inner"));
        }

        @Override
        public Node render(PageContext context, Node children) {
            return children;
        }
    }
}
