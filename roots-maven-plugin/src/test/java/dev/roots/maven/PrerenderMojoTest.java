package dev.roots.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PrerenderMojoTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesApplicationResourcesThroughThePluginBoundary() throws Exception {
        var mojo = new PrerenderMojo();
        mojo.applicationClass = dev.roots.maven.fixture.Application.class.getName();
        mojo.outputDirectory = temporaryDirectory.toFile();

        var previous = Thread.currentThread().getContextClassLoader();
        var report = mojo.generate(getClass().getClassLoader());

        assertEquals(previous, Thread.currentThread().getContextClassLoader());
        assertEquals(1, report.routes().size());
        assertEquals("/", report.routes().getFirst().path());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve(
                "META-INF/roots/prerender/dev.roots.maven.fixture.Application.index")));
    }

    @Test
    void rejectsMissingApplicationClassAndDevelopmentConfiguration() {
        var missing = new PrerenderMojo();
        missing.outputDirectory = temporaryDirectory.toFile();
        assertThrows(MojoExecutionException.class, () -> missing.generate(getClass().getClassLoader()));

        var development = new PrerenderMojo();
        development.applicationClass = dev.roots.maven.fixture.Application.class.getName();
        development.configFactory = DevelopmentConfig.class.getName();
        development.outputDirectory = temporaryDirectory.toFile();
        var failure = assertThrows(MojoExecutionException.class,
                () -> development.generate(getClass().getClassLoader()));
        assertTrue(failure.getMessage().contains("disable development mode"), failure.getMessage());
    }

    public static final class DevelopmentConfig implements dev.roots.PrerenderConfigFactory {
        @Override
        public dev.roots.RootsConfig create(Class<?> applicationClass) {
            return dev.roots.RootsConfig.forApplication(applicationClass).development(true).build();
        }
    }
}
