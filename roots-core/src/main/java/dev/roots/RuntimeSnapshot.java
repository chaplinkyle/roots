package dev.roots;

/** A point-in-time view of the built-in server's operational state.
 * @param running whether the listener is running
 * @param handledRequests completed exchanges
 * @param liveViews current live browser views
 * @param sessions current sessions
 * @param acceptingRequests whether readiness admits work
 * @param rejectedRequests admission-control rejections
 * @param activeRequests currently executing exchanges
 * @param peakActiveRequests peak simultaneous exchanges
 * @param maxLiveViews configured live-view limit
 * @param maxSessions configured session limit
 * @param maxConcurrentRequests configured concurrent-request limit */
public record RuntimeSnapshot(
        boolean running,
        long handledRequests,
        int liveViews,
        int sessions,
        boolean acceptingRequests,
        long rejectedRequests,
        int activeRequests,
        int peakActiveRequests,
        int maxLiveViews,
        int maxSessions,
        int maxConcurrentRequests
) {
    /** Preserves the snapshot constructor from before request admission control.
     * @param running whether the listener is running
     * @param handledRequests completed exchanges
     * @param liveViews current live browser views
     * @param sessions current sessions
     * @param acceptingRequests whether readiness admits work
     * @param rejectedRequests admission-control rejections
     * @param activeRequests currently executing exchanges
     * @param peakActiveRequests peak simultaneous exchanges
     * @param maxLiveViews configured live-view limit
     * @param maxSessions configured session limit */
    public RuntimeSnapshot(
            boolean running,
            long handledRequests,
            int liveViews,
            int sessions,
            boolean acceptingRequests,
            long rejectedRequests,
            int activeRequests,
            int peakActiveRequests,
            int maxLiveViews,
            int maxSessions
    ) {
        this(
                running,
                handledRequests,
                liveViews,
                sessions,
                acceptingRequests,
                rejectedRequests,
                activeRequests,
                peakActiveRequests,
                maxLiveViews,
                maxSessions,
                Integer.MAX_VALUE
        );
    }

    /** Preserves the original operational snapshot constructor.
     * @param running whether the listener is running
     * @param handledRequests completed exchanges
     * @param liveViews current live browser views
     * @param sessions current sessions */
    public RuntimeSnapshot(boolean running, long handledRequests, int liveViews, int sessions) {
        this(
                running,
                handledRequests,
                liveViews,
                sessions,
                running,
                0,
                0,
                0,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE
        );
    }
}
