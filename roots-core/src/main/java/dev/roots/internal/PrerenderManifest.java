package dev.roots.internal;

import dev.roots.RootsConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PrerenderManifest {
    private static final int MAX_INDEX_BYTES = 1_048_576;

    private PrerenderManifest() {
    }

    static List<Entry> load(RootsConfig config) {
        var resourceName = "META-INF/roots/prerender/" + config.applicationClass().getName() + ".index";
        var loader = config.applicationClass().getClassLoader();
        try {
            var resources = Collections.list(loader.getResources(resourceName));
            if (resources.isEmpty()) {
                return List.of();
            }
            if (resources.size() > 1) {
                throw invalid(resourceName, "multiple manifests found: " + resources);
            }
            final byte[] bytes;
            try (var input = resources.getFirst().openStream()) {
                bytes = input.readNBytes(MAX_INDEX_BYTES + 1);
            }
            if (bytes.length > MAX_INDEX_BYTES) {
                throw invalid(resourceName, "manifest exceeds " + MAX_INDEX_BYTES + " bytes");
            }
            return parse(config, resourceName, new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read Roots prerender manifest " + resourceName, exception);
        }
    }

    static List<Entry> parse(RootsConfig config, String resourceName, String content) {
        var lines = content.replace("\r\n", "\n").split("\n", -1);
        if (lines.length == 0) {
            throw invalid(resourceName, "missing header");
        }
        var header = lines[0].split("\t", -1);
        if (header.length != 3
                || !header[0].equals(PrerenderEngine.HEADER)
                || !header[1].equals(PrerenderEngine.VERSION)
                || !header[2].equals(config.applicationClass().getName())) {
            throw invalid(resourceName, "unsupported, malformed, or mismatched header");
        }
        var entries = new ArrayList<Entry>();
        var seen = new java.util.HashSet<String>();
        for (var index = 1; index < lines.length; index++) {
            var line = lines[index];
            if (line.isEmpty() && index == lines.length - 1) {
                continue;
            }
            var fields = line.split("\t", -1);
            if (fields.length != 3) {
                throw invalid(resourceName, "row " + (index + 1) + " must have three fields");
            }
            var path = fields[0];
            if (!validConcretePath(path) || !seen.add(path)) {
                throw invalid(resourceName, "non-canonical or duplicate path at row " + (index + 1));
            }
            final long seconds;
            try {
                seconds = Long.parseLong(fields[1]);
            } catch (NumberFormatException exception) {
                throw invalid(resourceName, "invalid revalidation interval at row " + (index + 1));
            }
            if (seconds < 0) {
                throw invalid(resourceName, "negative revalidation interval at row " + (index + 1));
            }
            var resource = fields[2];
            var prefix = "META-INF/roots/prerender/" + config.applicationClass().getName() + "/";
            if (!resource.startsWith(prefix)
                    || !resource.endsWith(".html")
                    || resource.indexOf('\\') >= 0
                    || resource.contains("..")) {
                throw invalid(resourceName, "invalid HTML resource at row " + (index + 1));
            }
            var html = loadHtml(config, resource, resourceName);
            entries.add(new Entry(path, seconds, html));
            if (entries.size() > PrerenderEngine.MAX_ROUTES) {
                throw invalid(resourceName, "manifest exceeds " + PrerenderEngine.MAX_ROUTES + " routes");
            }
        }
        return List.copyOf(entries);
    }

    private static String loadHtml(RootsConfig config, String resource, String manifest) {
        try (var input = config.applicationClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw invalid(manifest, "missing HTML resource " + resource);
            }
            var bytes = input.readNBytes(PrerenderEngine.MAX_HTML_BYTES + 1);
            if (bytes.length > PrerenderEngine.MAX_HTML_BYTES) {
                throw invalid(manifest, "HTML resource exceeds " + PrerenderEngine.MAX_HTML_BYTES + " bytes");
            }
            var html = new String(bytes, StandardCharsets.UTF_8);
            if (!html.contains("data-roots-static")) {
                throw invalid(manifest, "HTML resource is not a Roots static page: " + resource);
            }
            return html;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + resource + " from " + manifest, exception);
        }
    }

    private static boolean validConcretePath(String path) {
        if (path == null || !path.startsWith("/") || (path.length() > 1 && path.endsWith("/"))
                || path.indexOf('\\') >= 0 || path.chars().anyMatch(Character::isISOControl)
                || path.equals("/_roots") || path.startsWith("/_roots/")) {
            return false;
        }
        try {
            var uri = java.net.URI.create(path);
            return uri.isAbsolute()
                    ? false
                    : uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && path.equals(uri.getRawPath())
                    && path.equals(uri.normalize().getRawPath());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static IllegalStateException invalid(String resource, String reason) {
        return new IllegalStateException("Invalid Roots prerender manifest " + resource + ": " + reason);
    }

    record Entry(String path, long revalidateSeconds, String html) {
    }
}
