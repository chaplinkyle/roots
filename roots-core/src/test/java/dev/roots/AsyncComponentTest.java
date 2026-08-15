package dev.roots;

import dev.roots.html.HtmlRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.roots.html.Html.div;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncComponentTest {
    @Test
    void replacesFallbackAfterVirtualThreadCompletes() throws Exception {
        var updated = new CountDownLatch(1);
        var context = new PageContext("/", Map.of(), Map.of(), new Session("test"));
        context.attachUpdater(mutation -> {
            mutation.run();
            updated.countDown();
        });
        var component = AsyncComponent.of(
                () -> "loaded",
                value -> div(value),
                div("loading")
        );

        var first = HtmlRenderer.render(component, context);
        assertTrue(first.html().contains("loading"));
        assertTrue(updated.await(2, TimeUnit.SECONDS));
        var second = HtmlRenderer.render(component, context);
        assertTrue(second.html().contains("loaded"));
    }
}
