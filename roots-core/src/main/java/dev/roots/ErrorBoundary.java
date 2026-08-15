package dev.roots;

import dev.roots.html.Node;

import java.util.Objects;
import java.util.function.BiFunction;

public final class ErrorBoundary implements Node {
    private final Node children;
    private final BiFunction<Throwable, PageContext, Node> fallback;

    public ErrorBoundary(Node children, BiFunction<Throwable, PageContext, Node> fallback) {
        this.children = Objects.requireNonNull(children);
        this.fallback = Objects.requireNonNull(fallback);
    }

    public Node children() {
        return children;
    }

    public Node fallback(Throwable error, PageContext context) {
        return fallback.apply(error, context);
    }
}
