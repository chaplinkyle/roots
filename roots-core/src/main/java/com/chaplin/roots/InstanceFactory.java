package com.chaplin.roots;

import java.lang.reflect.InvocationTargetException;

/**
 * Creates convention-discovered pages, layouts, and API routes. Applications
 * can bridge this SPI to a dependency-injection container without coupling
 * Roots Core to that container.
 */
@FunctionalInterface
public interface InstanceFactory {
    /** Creates one convention-discovered type.
     * @param type concrete class to create
     * @return created instance
     * @throws Exception when creation fails */
    Object create(Class<?> type) throws Exception;

    /**
     * Releases a convention instance previously returned by {@link #create(Class)}.
     * Container adapters can invoke destruction callbacks; reflection-created
     * instances require no framework cleanup by default.
     *
     * @param instance instance whose Roots-owned scope has ended
     * @throws Exception when container destruction fails
     */
    default void destroy(Object instance) throws Exception {
    }

    /** Creates and type-checks a convention-discovered instance.
     * @param <T> required supertype
     * @param type concrete class to create
     * @return created instance
     * @throws Exception when creation fails */
    default <T> T instantiate(Class<? extends T> type) throws Exception {
        var instance = create(type);
        if (instance == null) {
            throw new IllegalStateException("InstanceFactory returned null for " + type.getName());
        }
        if (!type.isInstance(instance)) {
            throw new IllegalStateException("InstanceFactory returned " + instance.getClass().getName()
                    + " for " + type.getName());
        }
        return type.cast(instance);
    }

    /** Creates instances through accessible no-argument constructors.
     * @return reflection-backed factory */
    static InstanceFactory reflection() {
        return type -> {
            try {
                var constructor = type.getDeclaredConstructor();
                if (!constructor.trySetAccessible()) {
                    throw new IllegalStateException(
                            "Convention class needs an accessible no-argument constructor: " + type.getName()
                    );
                }
                return constructor.newInstance();
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException(
                        "Convention class needs a no-argument constructor or a configured InstanceFactory: "
                                + type.getName(),
                        exception
                );
            } catch (InvocationTargetException exception) {
                switch (exception.getCause()) {
                    case Exception cause -> throw cause;
                    case Error cause -> throw cause;
                    case Throwable cause -> throw new IllegalStateException("Could not create " + type.getName(), cause);
                }
            } catch (InstantiationException | IllegalAccessException exception) {
                throw new IllegalStateException("Could not create " + type.getName(), exception);
            }
        };
    }
}
