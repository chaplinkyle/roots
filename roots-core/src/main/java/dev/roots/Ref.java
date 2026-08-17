package dev.roots;

import java.util.concurrent.atomic.AtomicLong;

/** A stable reference to an element rendered by a live Java component. */
public final class Ref {
    private static final AtomicLong IDS = new AtomicLong();
    private final String id = "ref-" + Long.toUnsignedString(IDS.incrementAndGet(), 36);

    private Ref() {
    }

    /** Creates a unique element reference.
     * @return reference */
    public static Ref create() {
        return new Ref();
    }

    /** Returns the browser-safe reference identifier.
     * @return identifier */
    public String id() {
        return id;
    }
}
