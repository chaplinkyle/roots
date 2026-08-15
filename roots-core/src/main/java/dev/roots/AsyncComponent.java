package dev.roots;

import dev.roots.annotation.ViewComponent;
import dev.roots.html.Node;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Suspense-like server component. Its loader runs on a virtual thread, renders
 * a fallback immediately, then asks the live view to patch when work completes.
 */
@ViewComponent("async")
public final class AsyncComponent<T> implements Component {
    private final Callable<? extends T> loader;
    private final Function<? super T, ? extends Node> content;
    private final Node fallback;
    private final BiFunction<Throwable, PageContext, ? extends Node> failure;
    private volatile boolean started;
    private volatile boolean complete;
    private volatile T value;
    private volatile Throwable error;
    private volatile Thread task;

    private AsyncComponent(
            Callable<? extends T> loader,
            Function<? super T, ? extends Node> content,
            Node fallback,
            BiFunction<Throwable, PageContext, ? extends Node> failure
    ) {
        this.loader = Objects.requireNonNull(loader);
        this.content = Objects.requireNonNull(content);
        this.fallback = Objects.requireNonNull(fallback);
        this.failure = Objects.requireNonNull(failure);
    }

    public static <T> AsyncComponent<T> of(
            Callable<? extends T> loader,
            Function<? super T, ? extends Node> content,
            Node fallback
    ) {
        return new AsyncComponent<>(loader, content, fallback,
                (error, context) -> fallback);
    }

    public AsyncComponent<T> onFailure(BiFunction<Throwable, PageContext, ? extends Node> failure) {
        return new AsyncComponent<>(loader, content, fallback, failure);
    }

    @Override
    public Node render(PageContext context) {
        start(context);
        if (!complete) {
            return fallback;
        }
        return error == null ? content.apply(value) : failure.apply(error, context);
    }

    @Override
    public void onUnmount(PageContext context) {
        var running = task;
        if (running != null) {
            running.interrupt();
        }
    }

    private synchronized void start(PageContext context) {
        if (started) {
            return;
        }
        started = true;
        task = Thread.ofVirtual().name("roots-async-component").start(() -> {
            try {
                var loaded = loader.call();
                context.update(() -> {
                    value = loaded;
                    complete = true;
                });
            } catch (Throwable throwable) {
                context.update(() -> {
                    error = throwable;
                    complete = true;
                });
            }
        });
    }
}
