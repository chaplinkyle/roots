package dev.roots.internal;

import dev.roots.html.RenderedTree;

import java.util.Comparator;
import java.util.Objects;

/** Selects the narrowest component boundary that exactly explains a render change. */
final class RenderedTreePatch {
    private static final String PORTAL_MARKER = "<template data-roots-portal=";

    private RenderedTreePatch() {
    }

    static Patch between(RenderedTree previous, RenderedTree next) {
        Objects.requireNonNull(next, "next");
        if (previous == null) {
            return Patch.root(next.html());
        }
        if (previous.html().equals(next.html())) {
            return Patch.none();
        }
        if (previous.html().contains(PORTAL_MARKER) || next.html().contains(PORTAL_MARKER)) {
            return Patch.root(next.html());
        }
        return next.componentHtml().entrySet().stream()
                .filter(entry -> previous.componentHtml().containsKey(entry.getKey()))
                .map(entry -> componentPatch(previous, next, entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull)
                .min(Comparator.comparingInt(patch -> patch.html().length()))
                .orElseGet(() -> Patch.root(next.html()));
    }

    private static Patch componentPatch(RenderedTree previous, RenderedTree next, String id, String nextRegion) {
        var previousRegion = previous.componentHtml().get(id);
        var previousStart = uniqueIndex(previous.html(), previousRegion);
        var nextStart = uniqueIndex(next.html(), nextRegion);
        if (previousStart < 0 || nextStart < 0) {
            return null;
        }
        if (!previous.html().regionMatches(0, next.html(), 0, Math.min(previousStart, nextStart))
                || previousStart != nextStart) {
            return null;
        }
        var previousSuffix = previous.html().substring(previousStart + previousRegion.length());
        var nextSuffix = next.html().substring(nextStart + nextRegion.length());
        return previousSuffix.equals(nextSuffix) ? Patch.component(id, nextRegion) : null;
    }

    private static int uniqueIndex(String html, String region) {
        var first = html.indexOf(region);
        return first >= 0 && html.indexOf(region, first + 1) < 0 ? first : -1;
    }

    record Patch(String html, String scope) {
        static Patch root(String html) {
            return new Patch(Objects.requireNonNull(html), null);
        }

        static Patch component(String scope, String html) {
            return new Patch(Objects.requireNonNull(html), Objects.requireNonNull(scope));
        }

        static Patch none() {
            return new Patch(null, null);
        }

        boolean component() {
            return scope != null;
        }
    }
}
