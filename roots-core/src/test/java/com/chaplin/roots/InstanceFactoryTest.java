package com.chaplin.roots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InstanceFactoryTest {
    @Test
    void reflectionFactoryCreatesPrivateNoArgumentTypes() throws Exception {
        var created = InstanceFactory.reflection().instantiate(PrivateType.class);

        assertEquals("ready", created.value);
    }

    @Test
    void reflectionFactoryExplainsConstructorInjectionRequirement() {
        var failure = assertThrows(
                IllegalStateException.class,
                () -> InstanceFactory.reflection().instantiate(ConstructorOnlyType.class)
        );

        assertTrue(failure.getMessage().contains("configured InstanceFactory"));
    }

    @Test
    void customFactoryMustReturnTheRequestedType() {
        InstanceFactory wrong = ignored -> "not the requested type";
        InstanceFactory missing = ignored -> null;

        assertThrows(IllegalStateException.class, () -> wrong.instantiate(PrivateType.class));
        assertThrows(IllegalStateException.class, () -> missing.instantiate(PrivateType.class));
    }

    private static final class PrivateType {
        private final String value = "ready";

        private PrivateType() {
        }
    }

    private static final class ConstructorOnlyType {
        private ConstructorOnlyType(String ignored) {
        }
    }
}
