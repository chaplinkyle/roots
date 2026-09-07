package com.chaplin.roots.internal;

import com.sun.management.ThreadMXBean;
import com.chaplin.roots.Component;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.Session;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.HtmlRenderer;
import com.chaplin.roots.html.Node;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static com.chaplin.roots.html.Html.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in measurement; timings are observations, not portable pass/fail SLAs. */
@Tag("benchmark")
final class RenderBenchmarkTest {
    @Test
    void measuresRenderingAndPatchSelectionForBusinessTables() {
        for (var rows : new int[]{100, 1_000}) measure(rows);
    }

    private void measure(int count) {
        var context = new PageContext("/benchmark", Map.of(), Map.of(), new Session("benchmark"));
        var rows = new ArrayList<Row>();
        for (int index = 0; index < count; index++) rows.add(new Row(index));
        var root = table(tbody(rows));
        var previous = HtmlRenderer.render(root, context);
        var iterations = Integer.getInteger("roots.benchmark.iterations", 200);
        assertTrue(iterations > 0);
        var renderNanos = new long[iterations];
        var patchNanos = new long[iterations];
        var bean = ManagementFactory.getThreadMXBean();
        var allocation = bean instanceof ThreadMXBean extended && extended.isThreadAllocatedMemorySupported()
                ? extended : null;
        if (allocation != null) allocation.setThreadAllocatedMemoryEnabled(true);
        long bytes = 0;
        long total = 0;
        int patchBytes = 0;
        for (int iteration = -100; iteration < iterations; iteration++) {
            rows.get(Math.floorMod(iteration, count)).revision++;
            var beforeBytes = allocation == null ? 0 : allocation.getCurrentThreadAllocatedBytes();
            var start = System.nanoTime();
            var next = HtmlRenderer.render(root, context);
            var rendered = System.nanoTime();
            var patch = RenderedTreePatch.between(previous, next);
            var finished = System.nanoTime();
            assertTrue(patch.component(), "Exactly one row changed");
            assertTrue(patch.html().length() < next.html().length() / count * 2);
            if (iteration >= 0) {
                renderNanos[iteration] = rendered - start;
                patchNanos[iteration] = finished - rendered;
                total += finished - start;
                bytes += allocation == null ? 0 : allocation.getCurrentThreadAllocatedBytes() - beforeBytes;
                patchBytes = patch.html().length();
            }
            previous = next;
        }
        Arrays.sort(renderNanos);
        Arrays.sort(patchNanos);
        var p95 = Math.min(iterations - 1, (int) Math.ceil(iterations * .95) - 1);
        System.out.printf(java.util.Locale.ROOT,
                "Roots render benchmark: rows=%d iterations=%d htmlChars=%d patchChars=%d "
                        + "meanTotalMs=%.3f p95RenderMs=%.3f p95PatchMs=%.3f allocatedBytesPerAction=%d%n",
                count, iterations, previous.html().length(), patchBytes,
                total / (iterations * 1_000_000.0), renderNanos[p95] / 1_000_000.0,
                patchNanos[p95] / 1_000_000.0, allocation == null ? -1 : bytes / iterations);
    }

    private static final class Row implements Component {
        private final int id;
        private int revision;

        private Row(int id) { this.id = id; }

        @Override
        public Node render(PageContext context) {
            return tr(td("Account " + id), td("owner" + id + "@example.com"),
                    td("Enterprise"), td(revision),
                    td(button("Approve").onClick(this, "approve"))).key("account-" + id);
        }

        @ServerAction
        private void approve() { revision++; }
    }
}
