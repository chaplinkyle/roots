package com.chaplin.roots.load;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LatencyHistogramTest {
    @Test void boundsRoundingAndCountsConcurrentWriters() {
        var histogram = new LatencyHistogram();
        java.util.stream.IntStream.rangeClosed(1, 100_000).parallel().forEach(i -> histogram.add(i * 1000L));
        assertEquals(100_000, histogram.count());
        for (double q : new double[]{.5, .95, .99, 1}) {
            var actual = histogram.percentile(q).toNanos();
            var expected = (long) (100_000_000 * q);
            assertTrue(actual >= expected && actual <= expected * 1.01 + 1);
        }
        assertThrows(IllegalArgumentException.class, () -> histogram.add(-1));
    }
}
