package dev.roots.spring.boot.compatibility;

import dev.roots.servlet.RootsServlet;
import dev.roots.spring.SpringInstanceFactory;
import dev.roots.spring.boot.RootsAutoConfiguration;

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
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class AdapterPublicApiSnapshot {
    private static final List<Surface> SURFACES = List.of(
            new Surface(RootsServlet.class, "dev.roots.servlet"),
            new Surface(SpringInstanceFactory.class, "dev.roots.spring"),
            new Surface(RootsAutoConfiguration.class, "dev.roots.spring.boot")
    );

    private AdapterPublicApiSnapshot() {
    }

    static String capture() {
        var lines = new ArrayList<String>();
        SURFACES.stream()
                .flatMap(surface -> publicTypes(surface).stream())
                .distinct()
                .sorted(Comparator.comparing(Class::getName))
                .forEach(type -> describe(type, lines));
        return String.join("\n", lines) + "\n";
    }

    public static void main(String[] arguments) {
        System.out.print(capture());
    }

    private static List<Class<?>> publicTypes(Surface surface) {
        final Path classes;
        try {
            classes = Path.of(surface.anchor().getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Cannot locate compiled classes for " + surface.packageName(), exception);
        }
        if (Files.isDirectory(classes)) {
            return publicTypesInDirectory(surface, classes);
        }
        return publicTypesInJar(surface, classes);
    }

    private static List<Class<?>> publicTypesInDirectory(Surface surface, Path classes) {
        var packageRoot = classes.resolve(surface.packageName().replace('.', '/'));
        try (Stream<Path> paths = Files.walk(packageRoot)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .map(path -> className(classes, path))
                    .<Class<?>>map(name -> load(surface, name))
                    .filter(type -> isExposed(type, surface.packageName()))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot scan compiled classes for " + surface.packageName(), exception);
        }
    }

    private static List<Class<?>> publicTypesInJar(Surface surface, Path jarPath) {
        var packageRoot = surface.packageName().replace('.', '/') + "/";
        try (var jar = new JarFile(jarPath.toFile())) {
            return jar.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.startsWith(packageRoot) && name.endsWith(".class"))
                    .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                    .<Class<?>>map(name -> load(surface, name))
                    .filter(type -> isExposed(type, surface.packageName()))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot scan adapter API JAR " + jarPath, exception);
        }
    }

    private static String className(Path classes, Path path) {
        var relative = classes.relativize(path).toString();
        return relative.substring(0, relative.length() - ".class".length())
                .replace('/', '.')
                .replace('\\', '.');
    }

    private static Class<?> load(Surface surface, String name) {
        try {
            return Class.forName(name, false, surface.anchor().getClassLoader());
        } catch (ClassNotFoundException | LinkageError failure) {
            throw new IllegalStateException("Cannot load public API candidate " + name, failure);
        }
    }

    private static boolean isExposed(Class<?> type, String packageName) {
        if (type.isSynthetic() || type.isAnonymousClass() || type.isLocalClass()
                || !type.getPackageName().equals(packageName)
                && !type.getPackageName().startsWith(packageName + ".")) {
            return false;
        }
        if (!exposedModifiers(type.getModifiers())) {
            return false;
        }
        var enclosing = type.getEnclosingClass();
        return enclosing == null || isExposed(enclosing, packageName);
    }

    private static void describe(Class<?> type, List<String> lines) {
        lines.add("TYPE " + normalize(type.toGenericString()));
        Arrays.stream(type.getDeclaredAnnotations())
                .map(AdapterPublicApiSnapshot::annotation)
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
                .map(AdapterPublicApiSnapshot::normalize)
                .sorted()
                .map(value -> "  FIELD " + value)
                .forEach(lines::add);
        Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> exposedModifiers(constructor.getModifiers()) && !constructor.isSynthetic())
                .map(Constructor::toGenericString)
                .map(AdapterPublicApiSnapshot::normalize)
                .sorted()
                .map(value -> "  CONSTRUCTOR " + value)
                .forEach(lines::add);
        Arrays.stream(type.getDeclaredMethods())
                .filter(method -> exposedModifiers(method.getModifiers()) && !method.isSynthetic() && !method.isBridge())
                .map(AdapterPublicApiSnapshot::method)
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

    private record Surface(Class<?> anchor, String packageName) {
    }
}
