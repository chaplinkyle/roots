package com.chaplin.roots;

import com.chaplin.roots.html.HtmlRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.chaplin.roots.html.Html.div;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncComponentTest {
    @Test
    void replacesFallbackAfterVirtualThreadCompletes() throws Exception {
        for (var attempt = 0; attempt < 100; attempt++) {
            var updated = new CountDownLatch(1);
            var context = new PageContext("/", Map.of(), Map.of(), new Session("test-" + attempt));
            context.attachUpdater(mutation -> {
                mutation.run();
                updated.countDown();
            });
            var component = AsyncComponent.of(
                    () -> "loaded",
                    value -> div(value),
                    div("loading")
            );

            assertEquals("<div data-roots-component=\"c0\">loading</div>", HtmlRenderer.render(component, context).html());
            assertTrue(updated.await(2, TimeUnit.SECONDS));
            assertEquals("<div data-roots-component=\"c0\">loaded</div>", HtmlRenderer.render(component, context).html());
            component.onUnmount(context);
        }
    }

    @Test
    void rendersConfiguredFailureAfterLoaderThrows() throws Exception {
        var updated = new CountDownLatch(1);
        var context = new PageContext("/", Map.of(), Map.of(), new Session("failure"));
        context.attachUpdater(mutation -> {
            mutation.run();
            updated.countDown();
        });
        var component = AsyncComponent.<String>of(
                () -> { throw new IllegalStateException("loader failed"); },
                div()::child,
                div("loading")
        ).onFailure((error, ignored) -> div("Failure: ", error.getMessage()));

        assertEquals("<div data-roots-component=\"c0\">loading</div>", HtmlRenderer.render(component, context).html());
        assertTrue(updated.await(2, TimeUnit.SECONDS));
        assertEquals("<div data-roots-component=\"c0\">Failure: loader failed</div>",
                HtmlRenderer.render(component, context).html());
        component.onUnmount(context);
    }
}
