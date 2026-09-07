package com.chaplin.roots.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MountPathsTest {
    @Test
    void rewritesGeneratedStaticUrlAttributesWithoutChangingExternalUrls() {
        var html = "<a href=\"/\">root</a><img src=\"/logo.svg\">"
                + "<form action=\"/save\"></form><a href=\"//cdn.example/x\">cdn</a>"
                + "<a href=\"https://example.com\">external</a>";

        assertEquals(
                "<a href=\"/company/roots/\">root</a><img src=\"/company/roots/logo.svg\">"
                        + "<form action=\"/company/roots/save\"></form>"
                        + "<a href=\"//cdn.example/x\">cdn</a><a href=\"https://example.com\">external</a>",
                MountPaths.rewriteHtml(html, "/company/roots")
        );
        assertEquals(html, MountPaths.rewriteHtml(html, ""));
        var mounted = "<a href=\"/company/roots/already\">mounted</a>";
        assertEquals(mounted, MountPaths.rewriteHtml(mounted, "/company/roots"));

        var responsive = "<img srcset=\"/small.png 320w, /large.png 960w, //cdn.example/x.png 2x\">";
        assertEquals(
                "<img srcset=\"/company/roots/small.png 320w, /company/roots/large.png 960w, //cdn.example/x.png 2x\">",
                MountPaths.rewriteHtml(responsive, "/company/roots")
        );
    }
}
