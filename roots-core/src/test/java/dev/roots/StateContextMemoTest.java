package dev.roots;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StateContextMemoTest {
    @Test
    void stateUpdatesAndMemoizesByDependency() {
        var state = State.of(4);
        assertEquals(5, state.update(value -> value + 1));

        var calculations = new AtomicInteger();
        var memo = new Memo<Integer>();
        assertEquals(10, memo.compute("same", () -> calculations.incrementAndGet() * 10));
        assertEquals(10, memo.compute("same", () -> calculations.incrementAndGet() * 10));
        assertEquals(20, memo.compute("different", () -> calculations.incrementAndGet() * 10));
    }

    @Test
    void providesTypedComponentContext() {
        var locale = ContextKey.of("locale", () -> "en-US");
        var context = new PageContext("/", Map.of(), Map.of(), new Session("test"));

        assertEquals("en-US", context.context(locale));
        context.provide(locale, "fr-CA");
        assertEquals("fr-CA", context.context(locale));
    }
}
