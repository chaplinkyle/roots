package com.chaplin.roots.spring.boot;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

final class RootsApplicationRegistrar implements ImportBeanDefinitionRegistrar {
    static final String BEAN_NAME = "rootsApplicationDescriptor";

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        var attributes = importingClassMetadata.getAnnotationAttributes(EnableRoots.class.getName());
        if (attributes == null) {
            throw new IllegalStateException("Missing @EnableRoots metadata");
        }
        var applicationClass = (Class<?>) attributes.get("value");
        if (registry.containsBeanDefinition(BEAN_NAME)) {
            throw new IllegalStateException("Only one @EnableRoots application anchor may be registered");
        }
        var definition = new RootBeanDefinition(RootsApplicationDescriptor.class);
        definition.getConstructorArgumentValues().addIndexedArgumentValue(0, applicationClass);
        registry.registerBeanDefinition(BEAN_NAME, definition);
    }
}
