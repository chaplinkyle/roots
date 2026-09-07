package com.chaplin.roots.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

final class ClassPathScannerTest {
    @TempDir Path directory;

    @Test void discoversClassesInsidePackedWarClasspathRoot() throws Exception {
        verifyArchive("WEB-INF/classes/", false);
    }

    @Test void discoversClassesBehindTomcatWarResourceUrls() throws Exception {
        verifyArchive("WEB-INF/classes/", true);
    }

    @Test void discoversClassesInsideOrdinaryJar() throws Exception {
        verifyArchive("", false);
    }

    private void verifyArchive(String root, boolean tomcatProtocol) throws Exception {
        var packageName = "com.chaplin.roots.internal.scannerfixture";
        var packagePath = packageName.replace('.', '/');
        var archive = directory.resolve("application.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry(root + packagePath + "/"));
            output.closeEntry();
            output.putNextEntry(new JarEntry(root + packagePath + "/Sample.class"));
            try (var source = getClass().getResourceAsStream("/" + packagePath + "/Sample.class")) {
                assertNotNull(source);
                source.transferTo(output);
            }
            output.closeEntry();
        }
        var location = new URL("jar:" + archive.toUri().toURL() + "!/" + root);
        // A platform parent cannot satisfy the fixture from this test project's classpath.
        var tomcatResource = new URL(null, "war:" + archive.toUri().toURL() + "*/" + root + packagePath + "/",
                new java.net.URLStreamHandler() {
                    @Override protected java.net.URLConnection openConnection(URL ignored) throws java.io.IOException {
                        throw new java.io.IOException("The scanner must use the standard archive URL");
                    }
                });
        try (var loader = new URLClassLoader(new URL[]{location}, ClassLoader.getPlatformClassLoader()) {
            @Override public java.util.Enumeration<URL> getResources(String name) throws java.io.IOException {
                return tomcatProtocol && name.equals(packagePath)
                        ? java.util.Collections.enumeration(java.util.List.of(tomcatResource)) : super.getResources(name);
            }
        }) {
            var classes = ClassPathScanner.classes(packageName, loader);
            assertEquals(1, classes.size());
            var found = classes.iterator().next();
            assertEquals(packageName + ".Sample", found.getName());
            assertSame(loader, found.getClassLoader());
        }
    }
}
