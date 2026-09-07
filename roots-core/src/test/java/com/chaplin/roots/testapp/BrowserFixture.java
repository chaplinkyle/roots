package com.chaplin.roots.testapp;

import com.chaplin.roots.InstanceFactory;
import com.chaplin.roots.Roots;
import com.chaplin.roots.RootsConfig;

import java.lang.reflect.InvocationTargetException;

public final class BrowserFixture {
    private BrowserFixture() {
    }

    public static void main(String[] arguments) {
        InstanceFactory factory = type -> {
            try {
                return type.getDeclaredConstructor(String.class).newInstance("browser dependency");
            } catch (InvocationTargetException exception) {
                switch (exception.getCause()) {
                    case Exception cause -> throw cause;
                    case Error cause -> throw cause;
                    case Throwable cause -> throw new IllegalStateException(cause);
                }
            }
        };
        Roots.run(RootsConfig.forApplication(Application.class)
                .instanceFactory(factory)
                .arguments(arguments)
                .build());
    }
}
