package com.chaplin.roots.spring;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringInstanceFactoryTest {
    @Test
    void createsFreshConstructorInjectedInstances() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(GreetingService.class, () -> new GreetingService("hello"));
            context.refresh();

            var factory = new SpringInstanceFactory(context);
            var first = factory.instantiate(InjectedType.class);
            var second = factory.instantiate(InjectedType.class);

            assertEquals("hello roots", first.message());
            assertNotSame(first, second);
        }
    }

    @Test
    void rejectsMissingSpringContracts() {
        assertThrows(NullPointerException.class, () -> new SpringInstanceFactory((org.springframework.context.ApplicationContext) null));
        assertThrows(NullPointerException.class, () -> new SpringInstanceFactory((org.springframework.beans.factory.config.AutowireCapableBeanFactory) null));
    }

    static final class GreetingService {
        private final String greeting;

        GreetingService(String greeting) {
            this.greeting = greeting;
        }
    }

    static final class InjectedType {
        private final GreetingService service;

        InjectedType(GreetingService service) {
            this.service = service;
        }

        String message() {
            return service.greeting + " roots";
        }
    }
}
