package com.chaplin.roots.spring.boot;

import java.util.Objects;

record RootsApplicationDescriptor(Class<?> applicationClass) {
    RootsApplicationDescriptor {
        Objects.requireNonNull(applicationClass, "applicationClass");
    }
}
