package dev.roots.internal;

import dev.roots.html.RenderedTree;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RenderedTreePatchTest {
    @Test
    void usesTheNarrowestBoundaryThatExactlyExplainsTheChange() {
        var previous = tree(
                "<main><section data-roots-component=\"c0\"><button data-roots-component=\"c1\">0</button></section></main>",
                Map.of(
                        "c0", "<section data-roots-component=\"c0\"><button data-roots-component=\"c1\">0</button></section>",
                        "c1", "<button data-roots-component=\"c1\">0</button>"
                )
        );
        var next = tree(
                "<main><section data-roots-component=\"c0\"><button data-roots-component=\"c1\">1</button></section></main>",
                Map.of(
                        "c0", "<section data-roots-component=\"c0\"><button data-roots-component=\"c1\">1</button></section>",
                        "c1", "<button data-roots-component=\"c1\">1</button>"
                )
        );

        var patch = RenderedTreePatch.between(previous, next);

        assertTrue(patch.component());
        assertEquals("c1", patch.scope());
        assertEquals(next.componentHtml().get("c1"), patch.html());
    }

    @Test
    void fallsBackToTheRootWhenChangesEscapeAComponentOrRegionsAreAmbiguous() {
        var previous = tree(
                "<main>A<div data-roots-component=\"c0\">same</div></main>",
                Map.of("c0", "<div data-roots-component=\"c0\">same</div>")
        );
        var outsideChange = tree(
                "<main>B<div data-roots-component=\"c0\">same</div></main>",
                Map.of("c0", "<div data-roots-component=\"c0\">same</div>")
        );
        var duplicated = tree("<main><i>x</i><i>x</i></main>", Map.of("c0", "<i>x</i>"));

        var outsidePatch = RenderedTreePatch.between(previous, outsideChange);
        var ambiguousPatch = RenderedTreePatch.between(duplicated, tree(
                "<main><i>y</i><i>y</i></main>", Map.of("c0", "<i>y</i>")));

        assertFalse(outsidePatch.component());
        assertEquals(outsideChange.html(), outsidePatch.html());
        assertEquals("<main><i>y</i><i>y</i></main>", ambiguousPatch.html());
    }

    @Test
    void forcesRootPatchesForPortalsAndRepresentsNoDomChangesWithoutHtml() {
        var portalBefore = tree(
                "<main><template data-roots-portal=\"modal\">old</template></main>", Map.of());
        var portalAfter = tree(
                "<main><template data-roots-portal=\"modal\">new</template></main>", Map.of());

        var initial = RenderedTreePatch.between(null, portalBefore);
        var portal = RenderedTreePatch.between(portalBefore, portalAfter);
        var unchanged = RenderedTreePatch.between(portalAfter, portalAfter);

        assertEquals(portalBefore.html(), initial.html());
        assertEquals(portalAfter.html(), portal.html());
        assertNull(unchanged.html());
        assertNull(unchanged.scope());
    }

    private static RenderedTree tree(String html, Map<String, String> regions) {
        return new RenderedTree(html, Map.of(), List.of(), regions);
    }
}
