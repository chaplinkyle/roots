package dev.roots.internal;

import dev.roots.HeadMetadata;
import dev.roots.Layout;
import dev.roots.Metadata;
import dev.roots.OpenGraphMetadata;
import dev.roots.Page;
import dev.roots.PageContext;
import dev.roots.WebFont;
import dev.roots.annotation.PageMetadata;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

final class MetadataSupport {
    private MetadataSupport() {
    }

    static ResolvedMetadata resolve(Page page, PageContext context) {
        return resolve(page, List.of(), context);
    }

    static ResolvedMetadata resolve(Page page, List<? extends Layout> layouts, PageContext context) {
        var inherited = HeadMetadata.EMPTY;
        for (var layout : layouts) {
            inherited = merge(inherited, Objects.requireNonNull(
                    layout.headMetadata(context), "Layout head metadata for " + layout.getClass().getName()
            ));
        }
        var dynamic = merge(inherited,
                Objects.requireNonNull(page.headMetadata(context), "Page head metadata"));
        var annotation = page.getClass().getAnnotation(PageMetadata.class);
        if (annotation == null) {
            return new ResolvedMetadata(
                    Objects.requireNonNull(page.metadata(context), "Page metadata"),
                    dynamic
            );
        }
        var fonts = Arrays.stream(annotation.fonts())
                .map(font -> new WebFont(
                        font.family(), font.source(), font.weight(), font.style(), font.display(), font.preload()
                ))
                .toList();
        var document = new Metadata(annotation.title(), annotation.description(),
                java.util.List.of(annotation.stylesheets()), fonts);
        var dynamicOpenGraph = dynamic.openGraph();
        var openGraph = new OpenGraphMetadata(
                overlay(annotation.openGraphTitle(), dynamicOpenGraph.title()),
                overlay(annotation.openGraphDescription(), dynamicOpenGraph.description()),
                overlay(annotation.openGraphType(), dynamicOpenGraph.type()),
                overlay(annotation.openGraphUrl(), dynamicOpenGraph.url()),
                overlay(annotation.openGraphImage(), dynamicOpenGraph.image()),
                overlay(annotation.openGraphImageAlt(), dynamicOpenGraph.imageAlt()),
                overlay(annotation.openGraphSiteName(), dynamicOpenGraph.siteName())
        );
        var head = new HeadMetadata(
                overlay(annotation.canonical(), dynamic.canonical()),
                overlay(annotation.robots(), dynamic.robots()),
                overlay(annotation.themeColor(), dynamic.themeColor()),
                openGraph
        );
        return new ResolvedMetadata(document, head);
    }

    private static String overlay(String declared, String dynamic) {
        return declared == null || declared.isBlank() ? dynamic : declared;
    }

    private static HeadMetadata merge(HeadMetadata inherited, HeadMetadata overriding) {
        var inheritedOpenGraph = inherited.openGraph();
        var overridingOpenGraph = overriding.openGraph();
        return new HeadMetadata(
                overlay(overriding.canonical(), inherited.canonical()),
                overlay(overriding.robots(), inherited.robots()),
                overlay(overriding.themeColor(), inherited.themeColor()),
                new OpenGraphMetadata(
                        overlay(overridingOpenGraph.title(), inheritedOpenGraph.title()),
                        overlay(overridingOpenGraph.description(), inheritedOpenGraph.description()),
                        overlay(overridingOpenGraph.type(), inheritedOpenGraph.type()),
                        overlay(overridingOpenGraph.url(), inheritedOpenGraph.url()),
                        overlay(overridingOpenGraph.image(), inheritedOpenGraph.image()),
                        overlay(overridingOpenGraph.imageAlt(), inheritedOpenGraph.imageAlt()),
                        overlay(overridingOpenGraph.siteName(), inheritedOpenGraph.siteName())
                )
        );
    }
}
