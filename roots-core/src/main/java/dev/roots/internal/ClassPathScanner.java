package dev.roots.internal;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarFile;

final class ClassPathScanner {
    private ClassPathScanner() {
    }

    static Set<Class<?>> classes(String packageName, ClassLoader classLoader) {
        var names = new LinkedHashSet<String>();
        var packagePath = packageName.replace('.', '/');
        try {
            var resources = classLoader.getResources(packagePath);
            while (resources.hasMoreElements()) {
                var resource = resources.nextElement();
                switch (resource.getProtocol()) {
                    case "file" -> scanDirectory(Path.of(resource.toURI()), packageName, names);
                    case "jar" -> scanJar(((JarURLConnection) resource.openConnection()).getJarFile(), packagePath, names);
                    default -> { }
                }
            }
        } catch (IOException | URISyntaxException exception) {
            throw new IllegalStateException("Could not scan package " + packageName, exception);
        }

        var classes = new LinkedHashSet<Class<?>>();
        for (var name : names) {
            try {
                classes.add(Class.forName(name, false, classLoader));
            } catch (ClassNotFoundException | LinkageError exception) {
                throw new IllegalStateException("Could not load convention class " + name, exception);
            }
        }
        return classes;
    }

    private static void scanDirectory(Path root, String packageName, Set<String> names) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var files = Files.walk(root)) {
            files.filter(path -> path.getFileName().toString().endsWith(".class"))
                    .forEach(path -> {
                        var relative = root.relativize(path).toString().replace('\\', '.').replace('/', '.');
                        var suffix = relative.substring(0, relative.length() - ".class".length());
                        if (!suffix.endsWith("module-info") && !suffix.endsWith("package-info")) {
                            names.add(packageName + "." + suffix);
                        }
                    });
        }
    }

    private static void scanJar(JarFile jar, String packagePath, Set<String> names) {
        jar.stream()
                .map(entry -> entry.getName())
                .filter(name -> name.startsWith(packagePath + "/"))
                .filter(name -> name.endsWith(".class"))
                .filter(name -> !name.endsWith("module-info.class") && !name.endsWith("package-info.class"))
                .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                .forEach(names::add);
    }
}
