package dev.roots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class OptimisticEffectTest {
    @Test
    void createsEverySupportedTypedEffect() {
        var ref = Ref.create();

        assertEquals(OptimisticEffect.Type.HIDE, OptimisticEffect.hide(ref).type());
        assertEquals("pending", OptimisticEffect.text(ref, "pending").value());
        assertEquals("42", OptimisticEffect.value(ref, 42).value());
        assertEquals(OptimisticEffect.Type.DISABLE, OptimisticEffect.disable(ref).type());
        assertEquals(ref.id(), OptimisticEffect.disable(ref).target());
        assertNull(OptimisticEffect.disable(ref).value());
    }

    @Test
    void rejectsInvalidOrUnboundedWireValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new OptimisticEffect(OptimisticEffect.Type.HIDE, "bad target", null));
        assertThrows(NullPointerException.class,
                () -> new OptimisticEffect(OptimisticEffect.Type.TEXT, "valid", null));
        assertThrows(IllegalArgumentException.class,
                () -> new OptimisticEffect(OptimisticEffect.Type.DISABLE, "valid", "unexpected"));
        assertThrows(IllegalArgumentException.class,
                () -> new OptimisticEffect(OptimisticEffect.Type.VALUE, "valid", "x".repeat(16_385)));
    }
}
