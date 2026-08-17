package dev.roots;

import dev.roots.html.Node;

import java.util.Objects;
import java.util.function.BiFunction;

/** Isolates render failures and replaces failed content with a fallback tree. */
public final class ErrorBoundary implements Node {
    private final Node children;
    private final BiFunction<Throwable, PageContext, Node> fallback;

    /** Creates an error boundary.
     * @param children protected content
     * @param fallback failure renderer */
    public ErrorBoundary(Node children, BiFunction<Throwable, PageContext, Node> fallback) {
        this.children = Objects.requireNonNull(children);
        this.fallback = Objects.requireNonNull(fallback);
    }

    /** Returns the protected content.
     * @return child tree */
    public Node children() {
        return children;
    }

    /** Renders fallback content for a failure.
     * @param error render failure
     * @param context page context
     * @return fallback tree */
    public Node fallback(Throwable error, PageContext context) {
        return fallback.apply(error, context);
    }
}
