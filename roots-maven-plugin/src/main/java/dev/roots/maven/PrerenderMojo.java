package dev.roots.maven;

import dev.roots.PrerenderConfigFactory;
import dev.roots.PrerenderReport;
import dev.roots.Prerenderer;
import dev.roots.RootsConfig;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;

/** Generates classpath-packaged HTML for application pages annotated with {@code @Prerender}. */
@Mojo(name = "prerender", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class PrerenderMojo extends AbstractMojo {
    /** Fully qualified application convention-anchor class. */
    @Parameter(property = "roots.applicationClass", required = true)
    String applicationClass;

    /** Optional application class implementing {@link PrerenderConfigFactory}. */
    @Parameter(property = "roots.prerenderConfigFactory")
    String configFactory;

    /** Directory receiving generated classpath resources. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    File outputDirectory;

    /** Allows a build profile to disable generation. */
    @Parameter(property = "roots.skipPrerender", defaultValue = "false")
    boolean skip;

    /** Current Maven project, used to construct an isolated runtime class path. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** Creates the Mojo. */
    public PrerenderMojo() {
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping Roots prerender generation");
            return;
        }
        try (var loader = projectClassLoader()) {
            var report = generate(loader);
            getLog().info("Generated " + report.routes().size() + " Roots prerendered route(s) in "
                    + report.outputDirectory());
        } catch (MojoExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MojoExecutionException("Could not generate Roots prerendered pages", exception);
        }
    }

    PrerenderReport generate(ClassLoader loader) throws MojoExecutionException {
        if (applicationClass == null || applicationClass.isBlank()) {
            throw new MojoExecutionException("roots.applicationClass must name the application convention anchor");
        }
        if (outputDirectory == null) {
            throw new MojoExecutionException("Roots prerender outputDirectory is required");
        }
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            var anchor = Class.forName(applicationClass, true, loader);
            var config = configuration(anchor, loader);
            if (config.applicationClass() != anchor) {
                throw new MojoExecutionException("Prerender configuration uses "
                        + config.applicationClass().getName() + " instead of " + anchor.getName());
            }
            if (config.development()) {
                throw new MojoExecutionException("Prerender configuration must disable development mode");
            }
            return Prerenderer.generate(config, outputDirectory.toPath());
        } catch (MojoExecutionException exception) {
            throw exception;
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new MojoExecutionException("Could not load Roots application " + applicationClass, exception);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private RootsConfig configuration(Class<?> anchor, ClassLoader loader)
            throws ReflectiveOperationException, MojoExecutionException {
        if (configFactory == null || configFactory.isBlank()) {
            return RootsConfig.forApplication(anchor).development(false).build();
        }
        var factoryType = Class.forName(configFactory, true, loader);
        if (!PrerenderConfigFactory.class.isAssignableFrom(factoryType)) {
            throw new MojoExecutionException(configFactory + " does not implement "
                    + PrerenderConfigFactory.class.getName());
        }
        var constructor = factoryType.getDeclaredConstructor();
        if (!constructor.trySetAccessible()) {
            throw new MojoExecutionException("Prerender configuration factory needs an accessible no-argument "
                    + "constructor: " + configFactory);
        }
        var factory = (PrerenderConfigFactory) constructor.newInstance();
        var config = factory.create(anchor);
        if (config == null) {
            throw new MojoExecutionException("Prerender configuration factory returned null: " + configFactory);
        }
        return config;
    }

    private URLClassLoader projectClassLoader() throws MojoExecutionException {
        final var paths = new ArrayList<String>();
        try {
            paths.addAll(project.getRuntimeClasspathElements());
        } catch (DependencyResolutionRequiredException exception) {
            throw new MojoExecutionException("Could not resolve the application runtime class path", exception);
        }
        var urls = new ArrayList<URL>();
        for (var path : paths) {
            try {
                urls.add(new File(path).toURI().toURL());
            } catch (MalformedURLException exception) {
                throw new MojoExecutionException("Invalid application class path entry: " + path, exception);
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
    }
}
