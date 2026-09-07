package com.chaplin.roots;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BrowserEventTest {
    @Test
    void resolvesEverySupportedWireType() {
        for (var type : BrowserEvent.Type.values()) {
            assertEquals(type, BrowserEvent.Type.fromWireName(type.wireName()));
            assertEquals(type.wireName(), BrowserEvent.empty(type).type().wireName());
        }
        assertThrows(IllegalArgumentException.class, () -> BrowserEvent.Type.fromWireName("wheel"));
        assertThrows(IllegalArgumentException.class, () -> BrowserEvent.Type.fromWireName("CLICK"));
    }

    @Test
    void retainsTypedKeyboardModifierAndPointerDetails() {
        var browser = new BrowserEvent(
                BrowserEvent.Type.KEY_DOWN,
                Optional.of("Enter"),
                Optional.of("Enter"),
                true,
                true,
                false,
                true,
                OptionalInt.of(0),
                OptionalInt.of(120),
                OptionalInt.of(-25)
        );
        var event = new ActionEvent(browser, java.util.Map.of(), java.util.Map.of(),
                new Session("browser-event"), RootsCache.disabled());

        assertEquals("keydown", event.type());
        assertEquals("Enter", event.browser().key().orElseThrow());
        assertEquals("Enter", event.browser().code().orElseThrow());
        assertEquals(0, event.browser().button().orElseThrow());
        assertEquals(-25, event.browser().clientY().orElseThrow());
        assertFalse(event.browser().metaKey());
    }

    @Test
    void rejectsUnboundedOrInvalidMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new BrowserEvent(
                BrowserEvent.Type.INPUT,
                Optional.of("x".repeat(129)), Optional.empty(),
                false, false, false, false,
                OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new BrowserEvent(
                BrowserEvent.Type.CLICK,
                Optional.empty(), Optional.empty(),
                false, false, false, false,
                OptionalInt.of(32), OptionalInt.empty(), OptionalInt.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new BrowserEvent(
                BrowserEvent.Type.POINTER_DOWN,
                Optional.empty(), Optional.empty(),
                false, false, false, false,
                OptionalInt.empty(), OptionalInt.of(1_000_001), OptionalInt.empty()
        ));
    }
}
