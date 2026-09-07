package com.chaplin.roots.load;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Bounded logarithmic histogram, at most 1% bucket rounding; report after writers stop. */
public final class LatencyHistogram {
    private static final double LOG = Math.log(1.01);
    private final AtomicLongArray buckets = new AtomicLongArray(4096);
    private final LongAdder count = new LongAdder();
    private final LongAdder total = new LongAdder();
    private final AtomicLong max = new AtomicLong();

    public void add(long nanos) {
        if (nanos < 0) throw new IllegalArgumentException("Negative latency");
        int index = nanos <= 1 ? 0 : Math.min(buckets.length() - 1, (int) Math.ceil(Math.log(nanos) / LOG));
        buckets.incrementAndGet(index);
        total.add(nanos);
        max.accumulateAndGet(nanos, Math::max);
        count.increment();
    }
    public long count() { return count.sum(); }
    public Duration percentile(double quantile) {
        if (quantile <= 0 || quantile > 1 || count() == 0) throw new IllegalArgumentException("Empty histogram or invalid quantile");
        long target = (long) Math.ceil(count() * quantile), cumulative = 0;
        for (int index = 0; index < buckets.length(); index++) {
            cumulative += buckets.get(index);
            if (cumulative >= target) return Duration.ofNanos(Math.min(max.get(), (long) Math.ceil(Math.exp(index * LOG))));
        }
        throw new IllegalStateException("Histogram is still being written");
    }
    public String summary() {
        if (count() == 0) return "count=0";
        return String.format(java.util.Locale.ROOT, "count=%d meanMs=%.3f p50Ms=%.3f p95Ms=%.3f p99Ms=%.3f maxMs=%.3f",
                count(), total.sum() / (count() * 1e6), percentile(.5).toNanos() / 1e6,
                percentile(.95).toNanos() / 1e6, percentile(.99).toNanos() / 1e6, max.get() / 1e6);
    }
}
