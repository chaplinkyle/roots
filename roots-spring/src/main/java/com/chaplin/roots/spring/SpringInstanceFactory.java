package com.chaplin.roots.spring;

import com.chaplin.roots.InstanceFactory;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;

import java.util.Objects;

/**
 * Creates each Roots convention class as a fresh, fully initialized Spring bean.
 *
 * <p>Fresh instances preserve Roots' per-view page and layout state. Dependencies,
 * bean post-processors, and Spring initialization callbacks are still applied by
 * the owning application context.</p>
 */
public final class SpringInstanceFactory implements InstanceFactory {
    private final AutowireCapableBeanFactory beanFactory;

    /**
     * Creates an adapter from a Spring application context.
     *
     * @param applicationContext owning Spring context
     */
    public SpringInstanceFactory(ApplicationContext applicationContext) {
        this(Objects.requireNonNull(applicationContext, "applicationContext")
                .getAutowireCapableBeanFactory());
    }

    /**
     * Creates an adapter from Spring's bean-creation contract.
     *
     * @param beanFactory owning autowire-capable bean factory
     */
    public SpringInstanceFactory(AutowireCapableBeanFactory beanFactory) {
        this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory");
    }

    /**
     * Creates a fresh convention instance with Spring dependency injection and
     * bean post-processing.
     *
     * @param type concrete page, layout, or API-route class
     * @return initialized instance
     */
    @Override
    public Object create(Class<?> type) {
        return beanFactory.createBean(Objects.requireNonNull(type, "type"));
    }

    /**
     * Applies Spring destruction callbacks when the Roots-owned scope ends.
     *
     * @param instance convention instance leaving its live-view or request scope
     */
    @Override
    public void destroy(Object instance) {
        beanFactory.destroyBean(Objects.requireNonNull(instance, "instance"));
    }
}
