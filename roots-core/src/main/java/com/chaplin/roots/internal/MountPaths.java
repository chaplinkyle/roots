package com.chaplin.roots.internal;

import java.util.regex.Pattern;

final class MountPaths {
    private static final Pattern ROOT_URL_ATTRIBUTE = Pattern.compile(
            "(\\s(?:action|formaction|href|poster|src)=\")/(?!/)"
    );
    private static final Pattern SOURCE_SET_ATTRIBUTE = Pattern.compile("(\\ssrcset=\")([^\"]*)(\")");

    private MountPaths() {
    }

    static String rewriteHtml(String html, String mountPath) {
        if (mountPath.isEmpty()) {
            return html;
        }
        var matcher = ROOT_URL_ATTRIBUTE.matcher(html);
        var rewritten = new StringBuilder(html.length() + mountPath.length() * 4);
        var copiedThrough = 0;
        while (matcher.find()) {
            var urlStart = matcher.end() - 1;
            rewritten.append(html, copiedThrough, urlStart);
            if (!alreadyMounted(html, urlStart, mountPath)) {
                rewritten.append(mountPath);
            }
            copiedThrough = urlStart;
        }
        var rootAttributes = rewritten.append(html, copiedThrough, html.length()).toString();
        var sourceSets = SOURCE_SET_ATTRIBUTE.matcher(rootAttributes);
        var mounted = new StringBuilder(rootAttributes.length() + mountPath.length() * 4);
        while (sourceSets.find()) {
            sourceSets.appendReplacement(mounted, java.util.regex.Matcher.quoteReplacement(
                    sourceSets.group(1) + rewriteSourceSet(sourceSets.group(2), mountPath) + sourceSets.group(3)
            ));
        }
        sourceSets.appendTail(mounted);
        return mounted.toString();
    }

    private static String rewriteSourceSet(String sourceSet, String mountPath) {
        return java.util.Arrays.stream(sourceSet.split(","))
                .map(candidate -> {
                    var trimmed = candidate.trim();
                    var whitespace = trimmed.indexOf(' ');
                    var url = whitespace < 0 ? trimmed : trimmed.substring(0, whitespace);
                    var descriptor = whitespace < 0 ? "" : trimmed.substring(whitespace);
                    if (url.startsWith("/") && !url.startsWith("//")
                            && !(url.equals(mountPath) || url.startsWith(mountPath + "/"))) {
                        url = mountPath + url;
                    }
                    return url + descriptor;
                })
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static boolean alreadyMounted(String html, int start, String mountPath) {
        if (!html.startsWith(mountPath, start)) {
            return false;
        }
        var end = start + mountPath.length();
        return end == html.length() || html.charAt(end) == '/' || html.charAt(end) == '"';
    }
}
