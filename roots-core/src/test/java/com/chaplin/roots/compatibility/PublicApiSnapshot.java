package com.chaplin.roots.compatibility;

import com.chaplin.roots.Roots;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class PublicApiSnapshot {
    private static final String ROOT_PACKAGE = "com.chaplin.roots";
    private static final String INTERNAL_PACKAGE = "com.chaplin.roots.internal";

    private PublicApiSnapshot() {
    }

    static String capture() {
        var lines = new ArrayList<String>();
        for (var type : publicTypes()) {
            describe(type, lines);
        }
        return String.join("\n", lines) + "\n";
    }

    public static void main(String[] arguments) {
        System.out.print(capture());
    }

    private static List<Class<?>> publicTypes() {
        final Path classes;
        try {
            classes = Path.of(Roots.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Cannot locate compiled Roots classes", exception);
        }
        if (!Files.isDirectory(classes)) {
            throw new IllegalStateException("API baseline generation requires an exploded classes directory: " + classes);
        }
        var packageRoot = classes.resolve("com/chaplin/roots");
        try (Stream<Path> paths = Files.walk(packageRoot)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .map(path -> className(classes, path))
                    .filter(name -> !name.startsWith(INTERNAL_PACKAGE + "."))
                    .<Class<?>>map(PublicApiSnapshot::load)
                    .filter(PublicApiSnapshot::isExposed)
                    .sorted(Comparator.comparing(Class::getName))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot scan compiled Roots classes", exception);
        }
    }

    private static String className(Path classes, Path path) {
        var relative = classes.relativize(path).toString();
        return relative.substring(0, relative.length() - ".class".length())
                .replace('/', '.')
                .replace('\\', '.');
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, Roots.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError failure) {
            throw new IllegalStateException("Cannot load public API candidate " + name, failure);
        }
    }

    private static boolean isExposed(Class<?> type) {
        if (type.isSynthetic() || type.isAnonymousClass() || type.isLocalClass()
                || !type.getPackageName().equals(ROOT_PACKAGE)
                && !type.getPackageName().startsWith(ROOT_PACKAGE + ".")) {
            return false;
        }
        if (!exposedModifiers(type.getModifiers())) {
            return false;
        }
        var enclosing = type.getEnclosingClass();
        return enclosing == null || isExposed(enclosing);
    }

    private static void describe(Class<?> type, List<String> lines) {
        lines.add("TYPE " + normalize(type.toGenericString()));
        Arrays.stream(type.getDeclaredAnnotations())
                .map(PublicApiSnapshot::annotation)
                .sorted()
                .map(value -> "  ANNOTATION " + value)
                .forEach(lines::add);
        if (type.isSealed()) {
            lines.add("  PERMITS " + Arrays.stream(type.getPermittedSubclasses())
                    .map(Class::getName)
                    .sorted()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse(""));
        }
        Arrays.stream(type.getDeclaredFields())
                .filter(field -> exposedModifiers(field.getModifiers()) && !field.isSynthetic())
                .map(Field::toGenericString)
                .map(PublicApiSnapshot::normalize)
                .sorted()
                .map(value -> "  FIELD " + value)
                .forEach(lines::add);
        Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> exposedModifiers(constructor.getModifiers()) && !constructor.isSynthetic())
                .map(Constructor::toGenericString)
                .map(PublicApiSnapshot::normalize)
                .sorted()
                .map(value -> "  CONSTRUCTOR " + value)
                .forEach(lines::add);
        Arrays.stream(type.getDeclaredMethods())
                .filter(method -> exposedModifiers(method.getModifiers()) && !method.isSynthetic() && !method.isBridge())
                .map(PublicApiSnapshot::method)
                .sorted()
                .map(value -> "  METHOD " + value)
                .forEach(lines::add);
        lines.add("END " + type.getName());
    }

    private static boolean exposedModifiers(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static String method(Method method) {
        var description = normalize(method.toGenericString());
        var defaultValue = method.getDefaultValue();
        return defaultValue == null ? description : description + " DEFAULT " + value(defaultValue);
    }

    private static String annotation(Annotation annotation) {
        var values = Arrays.stream(annotation.annotationType().getDeclaredMethods())
                .sorted(Comparator.comparing(Method::getName))
                .map(method -> method.getName() + "=" + invoke(annotation, method))
                .toList();
        return "@" + annotation.annotationType().getName()
                + (values.isEmpty() ? "" : "(" + String.join(",", values) + ")");
    }

    private static String invoke(Annotation annotation, Method method) {
        try {
            return value(method.invoke(annotation));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot read annotation " + annotation, exception);
        }
    }

    private static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value.getClass().isArray()) {
            var entries = new ArrayList<String>();
            for (var index = 0; index < Array.getLength(value); index++) {
                entries.add(value(Array.get(value, index)));
            }
            return "[" + String.join(",", entries) + "]";
        }
        if (value instanceof Class<?> type) {
            return type.getName() + ".class";
        }
        if (value instanceof Enum<?> constant) {
            return constant.getDeclaringClass().getName() + "." + constant.name();
        }
        if (value instanceof Annotation annotation) {
            return annotation(annotation);
        }
        return String.valueOf(value);
    }

    private static String normalize(String value) {
        return value.replace('$', '.').replaceAll("\\s+", " ").trim();
    }
}
