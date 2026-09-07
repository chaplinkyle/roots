package com.chaplin.roots.internal;

import com.chaplin.roots.WebFont;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class AssetUrls {
    private AssetUrls() {
    }

    static String fontStylesheet(WebFont font) {
        return "/_roots/font.css?family=" + encode(font.family())
                + "&src=" + encode(font.source())
                + "&weight=" + encode(font.weight())
                + "&style=" + encode(font.style())
                + "&display=" + encode(font.display());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
