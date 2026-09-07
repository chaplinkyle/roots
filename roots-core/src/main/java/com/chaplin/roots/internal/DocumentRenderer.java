package com.chaplin.roots.internal;

import com.chaplin.roots.Metadata;
import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.html.HtmlRenderer;

final class DocumentRenderer {
    private DocumentRenderer() {
    }

    static String live(
            RootsConfig config,
            String viewId,
            String csrf,
            long revision,
            String html,
            ResolvedMetadata metadata,
            String mountPath
    ) {
        var developmentAttribute = config.development()
                ? " data-roots-development-generation=\""
                + DevelopmentEvents.current(config.applicationClass().getName()).version() + "\""
                : "";
        var developmentStyleLink = config.development()
                ? "<link rel=\"stylesheet\" href=\"" + mountPath + "/_roots/development.css\">"
                : "";
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  %s
                  <link rel="stylesheet" data-roots-framework-style href="%s/_roots/client.css">
                  %s
                  %s
                  %s
                  <script src="%s/_roots/client.js" defer></script>
                </head>
                <body>
                  <div id="roots" data-roots-view="%s" data-roots-csrf="%s" data-roots-protocol="%s" data-roots-revision="%d" data-roots-mount-path="%s"%s>%s</div>
                  <div class="roots-announcer" data-roots-announcer="polite" role="status" aria-live="polite" aria-atomic="true"></div>
                  <div class="roots-announcer" data-roots-announcer="assertive" role="alert" aria-live="assertive" aria-atomic="true"></div>
                </body>
                </html>
                """.formatted(
                HtmlRenderer.escapeText(metadata.document().title()),
                managedHead(metadata, mountPath),
                HtmlRenderer.escapeAttribute(mountPath),
                preloads(metadata.document(), mountPath),
                styles(metadata.document(), mountPath),
                developmentStyleLink,
                HtmlRenderer.escapeAttribute(mountPath),
                HtmlRenderer.escapeAttribute(viewId),
                HtmlRenderer.escapeAttribute(csrf),
                Protocol.VERSION,
                revision,
                HtmlRenderer.escapeAttribute(mountPath),
                developmentAttribute,
                html
        );
    }

    static String staticPage(String html, ResolvedMetadata metadata) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta name="generator" content="Roots prerender">
                  <title>%s</title>
                  %s
                  %s
                  %s
                </head>
                <body>
                  <div id="roots" data-roots-static>%s</div>
                </body>
                </html>
                """.formatted(
                HtmlRenderer.escapeText(metadata.document().title()),
                managedHead(metadata, ""),
                preloads(metadata.document(), ""),
                styles(metadata.document(), ""),
                html
        );
    }

    static String managedHead(ResolvedMetadata metadata, String mountPath) {
        var document = metadata.document();
        var head = metadata.head();
        var tags = new StringBuilder();
        metaName(tags, "description", document.description());
        if (!head.canonical().isEmpty()) {
            tags.append("<link rel=\"canonical\" data-roots-head=\"canonical\" href=\"")
                    .append(HtmlRenderer.escapeAttribute(externalPath(head.canonical(), mountPath)))
                    .append("\">");
        }
        metaName(tags, "robots", head.robots());
        metaName(tags, "theme-color", head.themeColor());

        var openGraph = head.openGraph();
        if (!openGraph.isEmpty()) {
            metaProperty(tags, "og:title", fallback(openGraph.title(), document.title()));
            metaProperty(tags, "og:description", fallback(openGraph.description(), document.description()));
            metaProperty(tags, "og:type", openGraph.type());
            metaProperty(tags, "og:url", externalPath(openGraph.url(), mountPath));
            metaProperty(tags, "og:image", externalPath(openGraph.image(), mountPath));
            metaProperty(tags, "og:image:alt", openGraph.imageAlt());
            metaProperty(tags, "og:site_name", openGraph.siteName());
        }
        return tags.toString();
    }

    private static void metaName(StringBuilder tags, String name, String content) {
        if (content.isEmpty() && !name.equals("description")) {
            return;
        }
        tags.append("<meta name=\"").append(name).append("\" data-roots-head=\"")
                .append(name).append("\" content=\"")
                .append(HtmlRenderer.escapeAttribute(content)).append("\">");
    }

    private static void metaProperty(StringBuilder tags, String property, String content) {
        if (content.isEmpty()) {
            return;
        }
        tags.append("<meta property=\"").append(property).append("\" data-roots-head=\"")
                .append(property).append("\" content=\"")
                .append(HtmlRenderer.escapeAttribute(content)).append("\">");
    }

    private static String fallback(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    private static String styles(Metadata metadata, String mountPath) {
        var styles = new StringBuilder();
        for (var stylesheet : metadata.stylesheets()) {
            styles.append("<link rel=\"stylesheet\" data-roots-style href=\"")
                    .append(HtmlRenderer.escapeAttribute(externalPath(stylesheet, mountPath)))
                    .append("\">");
        }
        for (var font : metadata.fonts()) {
            styles.append("<link rel=\"stylesheet\" data-roots-style data-roots-font href=\"")
                    .append(HtmlRenderer.escapeAttribute(externalPath(AssetUrls.fontStylesheet(font), mountPath)))
                    .append("\">");
        }
        return styles.toString();
    }

    private static String preloads(Metadata metadata, String mountPath) {
        var links = new StringBuilder();
        for (var font : metadata.fonts()) {
            if (font.preload()) {
                links.append("<link rel=\"preload\" data-roots-font-preload as=\"font\" type=\"")
                        .append(fontType(font.source()))
                        .append("\" crossorigin href=\"")
                        .append(HtmlRenderer.escapeAttribute(externalPath(font.source(), mountPath)))
                        .append("\">");
            }
        }
        return links.toString();
    }

    private static String fontType(String source) {
        var lower = source.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        return "font/otf";
    }

    private static String externalPath(String value, String mountPath) {
        if (mountPath.isEmpty() || !value.startsWith("/") || value.startsWith("//")
                || value.equals(mountPath) || value.startsWith(mountPath + "/")) {
            return value;
        }
        return mountPath + value;
    }
}
