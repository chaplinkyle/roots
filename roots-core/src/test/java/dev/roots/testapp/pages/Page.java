package dev.roots.testapp.pages;

import dev.roots.PageContext;
import dev.roots.ActionEvent;
import dev.roots.annotation.PageMetadata;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;
import dev.roots.ValidationException;
import dev.roots.testapp.FixtureProblem;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.link;

@PageMetadata(title = "Core fixture", description = "Core integration fixture", stylesheets = "/base.css")
public final class Page implements dev.roots.Page {
    public static final AtomicInteger MOUNTS = new AtomicInteger();
    public static final AtomicInteger UNMOUNTS = new AtomicInteger();
    private static volatile CountDownLatch parallelActionsEntered;
    private static volatile CountDownLatch parallelActionsReleased;

    private final String greeting;
    private int count;

    public Page(String greeting) {
        this.greeting = greeting;
    }

    @Override
    public Node render(PageContext context) {
        return div(
                h1(greeting),
                button("Count ", count).onClick(this, "increment"),
                button("Explode").onClick(this, "explode"),
                button("Validate").onClick(this, "validate"),
                button("Redirect").onClick(this, "redirect"),
                link("/themed", "Themed page")
        );
    }

    @ServerAction
    private void increment() {
        var entered = parallelActionsEntered;
        var released = parallelActionsReleased;
        if (entered != null && released != null) {
            entered.countDown();
            try {
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release parallel action fixture");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Parallel action fixture was interrupted", exception);
            }
        }
        count++;
    }

    public static void blockParallelActions(int count) {
        parallelActionsEntered = new CountDownLatch(count);
        parallelActionsReleased = new CountDownLatch(1);
    }

    public static boolean awaitParallelActions() throws InterruptedException {
        return parallelActionsEntered.await(5, TimeUnit.SECONDS);
    }

    public static void releaseParallelActions() {
        var released = parallelActionsReleased;
        if (released != null) {
            released.countDown();
        }
        parallelActionsEntered = null;
        parallelActionsReleased = null;
    }

    @ServerAction
    private void explode() {
        throw new FixtureProblem("mapped fixture failure");
    }

    @ServerAction
    private void validate() {
        throw new ValidationException("Please fix the highlighted fields", Map.of(
                "email", List.of("Enter a valid email", "Email must not contain <markup>"),
                "name", List.of("Name is required")
        ));
    }

    @ServerAction
    private void redirect(ActionEvent event) {
        event.redirect("/themed");
    }

    @Override
    public void onMount(PageContext context) {
        MOUNTS.incrementAndGet();
    }

    @Override
    public void onUnmount(PageContext context) {
        UNMOUNTS.incrementAndGet();
    }

    public static void reset() {
        MOUNTS.set(0);
        UNMOUNTS.set(0);
    }
}
