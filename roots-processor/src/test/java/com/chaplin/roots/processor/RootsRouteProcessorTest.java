package com.chaplin.roots.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootsRouteProcessorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesDeterministicManifestWithNormalizedRoutesAndLayoutChains() throws Exception {
        var compilation = compile("complete", completeApplication());

        assertTrue(compilation.success(), compilation.messages());
        assertEquals("""
                ROOTS_ROUTE_MANIFEST\t3\tsample.Application\tsample.pages\tsample.api
                API\t/api/health\tsample.api.health.Route\t
                ERROR_PAGE\t/\tsample.pages.ErrorPage\t
                NOT_FOUND\t/\tsample.pages.NotFound\tsample.pages.Layout
                PAGE\t/\tsample.pages.Page\tsample.pages.Layout
                PAGE\t/staff/{memberId}\tsample.pages.admin.Page\tsample.pages.Layout,sample.pages.admin.Layout
                """, Files.readString(compilation.classes()
                .resolve("META-INF/roots/routes/sample.Application.routes")));
    }

    @Test
    void packagesAndRunsAnApiOnlyApplicationWithoutSessions() throws Exception {
        var sources = baseApplication();
        sources.put("sample.api.health.Route", """
                package sample.api.health;
                @com.chaplin.roots.annotation.Stateless
                public final class Route implements com.chaplin.roots.ApiRoute {
                    public com.chaplin.roots.Response get(com.chaplin.roots.Request request) {
                        return com.chaplin.roots.Response.text(200, "machine-ready");
                    }
                }
                """);
        var compilation = compile("api-only", sources);
        assertTrue(compilation.success(), compilation.messages());
        assertEquals("""
                ROOTS_ROUTE_MANIFEST\t3\tsample.Application\tsample.pages\tsample.api
                API\t/api/health\tsample.api.health.Route\t
                """, Files.readString(compilation.classes()
                .resolve("META-INF/roots/routes/sample.Application.routes")));
        try (var loader = new java.net.URLClassLoader(
                new java.net.URL[] { compilation.classes().toUri().toURL() }, getClass().getClassLoader());
             var application = com.chaplin.roots.Roots.start(com.chaplin.roots.RootsConfig
                     .forApplication(loader.loadClass("sample.Application")).port(0).development(false).build());
             var client = java.net.http.HttpClient.newHttpClient()) {
            var response = client.send(java.net.http.HttpRequest.newBuilder(
                    application.uri().resolve("/api/health")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("machine-ready", response.body());
            assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
            assertEquals(0, application.runtimeSnapshot().sessions());
        }
    }

    @Test
    void discoversSpringBootEnableRootsWithoutDependingOnTheStarter() throws Exception {
        var sources = new LinkedHashMap<String, String>();
        sources.put("com.chaplin.roots.spring.boot.EnableRoots", """
                package com.chaplin.roots.spring.boot;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
                public @interface EnableRoots { Class<?> value(); }
                """);
        sources.put("sample.Application", "package sample; public final class Application {} ");
        sources.put("sample.BootConfig", """
                package sample;
                @com.chaplin.roots.spring.boot.EnableRoots(Application.class)
                public final class BootConfig {}
                """);
        sources.put("sample.pages.Page", pageSource("sample.pages", null));
        sources.put("sample.pages.NotFound", notFoundSource());
        sources.put("sample.pages.ErrorPage", errorPageSource());

        var compilation = compile("boot", sources);

        assertTrue(compilation.success(), compilation.messages());
        assertTrue(Files.exists(compilation.classes()
                .resolve("META-INF/roots/routes/sample.Application.routes")));
    }

    @Test
    void rejectsRoutesWhoseParameterNamesHideTheSameMatchShape() throws Exception {
        var sources = baseApplication();
        sources.put("sample.pages.one.Page", pageSource("sample.pages.one", "/items/{id}"));
        sources.put("sample.pages.two.Page", pageSource("sample.pages.two", "/items/{name}"));

        var compilation = compile("duplicate", sources);

        assertFalse(compilation.success());
        assertTrue(compilation.messages().contains("Duplicate page shape /items/{}"), compilation.messages());
        assertTrue(compilation.messages().contains("sample.pages.one.Page"), compilation.messages());
        assertTrue(compilation.messages().contains("sample.pages.two.Page"), compilation.messages());
    }

    @Test
    void rejectsInvalidAnnotatedAndConventionRoutesAtCompilation() throws Exception {
        var annotated = baseApplication();
        annotated.put("sample.pages.Page", pageSource("sample.pages", "relative"));
        var annotatedCompilation = compile("invalid-annotation", annotated);
        assertFalse(annotatedCompilation.success());
        assertTrue(annotatedCompilation.messages().contains("Routes must start with one '/'"));

        var convention = baseApplication();
        convention.put("sample.pages.$.Page", pageSource("sample.pages.$", null));
        var conventionCompilation = compile("invalid-convention", convention);
        assertFalse(conventionCompilation.success());
        assertTrue(conventionCompilation.messages().contains("Dynamic route packages need a name"));

        var invalidParameter = baseApplication();
        invalidParameter.put("sample.pages.$9id.Page", pageSource("sample.pages.$9id", null));
        var invalidParameterCompilation = compile("invalid-parameter", invalidParameter);
        assertFalse(invalidParameterCompilation.success());
        assertTrue(invalidParameterCompilation.messages().contains("Invalid route segment '{9id}'"),
                invalidParameterCompilation.messages());

        var duplicateParameter = baseApplication();
        duplicateParameter.put("sample.pages.Page", pageSource("sample.pages", "/{id}/orders/{id}"));
        var duplicateParameterCompilation = compile("duplicate-parameter", duplicateParameter);
        assertFalse(duplicateParameterCompilation.success());
        assertTrue(duplicateParameterCompilation.messages().contains("Duplicate route parameter 'id'"),
                duplicateParameterCompilation.messages());
    }

    @Test
    void rejectsRoutingAndPrerenderAnnotationsOnTheNotFoundConvention() throws Exception {
        var routed = baseApplication();
        routed.put("sample.pages.Page", pageSource("sample.pages", null));
        routed.put("sample.pages.NotFound", """
                package sample.pages;
                @com.chaplin.roots.annotation.Route("/missing")
                public final class NotFound implements com.chaplin.roots.Page {
                    public com.chaplin.roots.html.Node render(com.chaplin.roots.PageContext context) {
                        return com.chaplin.roots.html.Html.main("missing");
                    }
                }
                """);
        var routedCompilation = compile("routed-not-found", routed);
        assertFalse(routedCompilation.success());
        assertTrue(routedCompilation.messages().contains("NotFound page convention cannot declare @Route"),
                routedCompilation.messages());

        var prerendered = baseApplication();
        prerendered.put("sample.pages.Page", pageSource("sample.pages", null));
        prerendered.put("sample.pages.NotFound", """
                package sample.pages;
                @com.chaplin.roots.annotation.Prerender
                public final class NotFound implements com.chaplin.roots.Page {
                    public com.chaplin.roots.html.Node render(com.chaplin.roots.PageContext context) {
                        return com.chaplin.roots.html.Html.main("missing");
                    }
                }
                """);
        var prerenderedCompilation = compile("prerendered-not-found", prerendered);
        assertFalse(prerenderedCompilation.success());
        assertTrue(prerenderedCompilation.messages().contains("NotFound page convention cannot declare @Prerender"),
                prerenderedCompilation.messages());

        var authorizedError = baseApplication();
        authorizedError.put("sample.pages.Page", pageSource("sample.pages", null));
        authorizedError.put("sample.pages.ErrorPage", """
                package sample.pages;
                @com.chaplin.roots.annotation.Authorize("admin")
                public final class ErrorPage implements com.chaplin.roots.Page {
                    public com.chaplin.roots.html.Node render(com.chaplin.roots.PageContext context) {
                        return com.chaplin.roots.html.Html.main("error");
                    }
                }
                """);
        var authorizedErrorCompilation = compile("authorized-error-page", authorizedError);
        assertFalse(authorizedErrorCompilation.success());
        assertTrue(authorizedErrorCompilation.messages().contains(
                        "ErrorPage convention cannot declare @Route, @Prerender, or @Authorize"),
                authorizedErrorCompilation.messages());
    }

    @Test
    void rejectsEmptyAndMalformedAuthorizationPolicies() throws Exception {
        var empty = baseApplication();
        empty.put("sample.pages.Page", authorizedPageSource("@Authorize({})"));
        var emptyCompilation = compile("empty-policy", empty);
        assertFalse(emptyCompilation.success());
        assertTrue(emptyCompilation.messages().contains("@Authorize must name at least one policy"));

        var malformed = baseApplication();
        malformed.put("sample.pages.Page", authorizedPageSource("@Authorize(\"bad policy\")"));
        var malformedCompilation = compile("bad-policy", malformed);
        assertFalse(malformedCompilation.success());
        assertTrue(malformedCompilation.messages().contains("Invalid authorization policy"));
    }

    @Test
    void validatesPrerenderContractsAtCompilation() throws Exception {
        var negative = baseApplication();
        negative.put("sample.pages.Page", prerenderPageSource(
                "@Prerender(revalidateSeconds = -1)", null));
        var negativeCompilation = compile("prerender-negative", negative);
        assertFalse(negativeCompilation.success());
        assertTrue(negativeCompilation.messages().contains("revalidateSeconds cannot be negative"),
                negativeCompilation.messages());

        var missingProvider = baseApplication();
        missingProvider.put("sample.pages.Page", prerenderPageSource("@Prerender", "/products/{id}"));
        var missingProviderCompilation = compile("prerender-missing-provider", missingProvider);
        assertFalse(missingProviderCompilation.success());
        assertTrue(missingProviderCompilation.messages().contains("requires a StaticPathProvider"),
                missingProviderCompilation.messages());

        var staticProvider = baseApplication();
        staticProvider.put("sample.ProductPaths", pathProviderSource());
        staticProvider.put("sample.pages.Page", prerenderPageSource(
                "@Prerender(paths = sample.ProductPaths.class)", null));
        var staticProviderCompilation = compile("prerender-static-provider", staticProvider);
        assertFalse(staticProviderCompilation.success());
        assertTrue(staticProviderCompilation.messages().contains("cannot declare a StaticPathProvider"),
                staticProviderCompilation.messages());

        var authorizedLayout = baseApplication();
        authorizedLayout.put("sample.pages.Layout", authorizedLayoutSource());
        authorizedLayout.put("sample.pages.Page", prerenderPageSource("@Prerender", null));
        var authorizedCompilation = compile("prerender-authorized", authorizedLayout);
        assertFalse(authorizedCompilation.success());
        assertTrue(authorizedCompilation.messages().contains("cannot have page or layout authorization"),
                authorizedCompilation.messages());

        var valid = baseApplication();
        valid.put("sample.ProductPaths", pathProviderSource());
        valid.put("sample.pages.Page", prerenderPageSource(
                "@Prerender(paths = sample.ProductPaths.class, revalidateSeconds = 60)",
                "/products/{id}"));
        var validCompilation = compile("prerender-valid", valid);
        assertTrue(validCompilation.success(), validCompilation.messages());
    }

    @Test
    void rejectsMultipleConcreteLayoutsInOnePackage() throws Exception {
        var sources = baseApplication();
        sources.put("sample.pages.Page", pageSource("sample.pages", null));
        sources.put("sample.pages.First", nestedLayoutSource("First"));
        sources.put("sample.pages.Second", nestedLayoutSource("Second"));

        var compilation = compile("duplicate-layout", sources);

        assertFalse(compilation.success());
        assertTrue(compilation.messages().contains("Multiple concrete Layout classes in package sample.pages"),
                compilation.messages());
        assertFalse(Files.exists(compilation.classes()
                .resolve("META-INF/roots/routes/sample.Application.routes")));
    }

    @Test
    void requiresAtLeastOneConcretePageAndIgnoresUnmarkedCompilations() throws Exception {
        var noPage = baseApplication();
        noPage.put("sample.pages.Page", """
                package sample.pages;
                public abstract class Page implements com.chaplin.roots.Page {}
                """);
        var noPageCompilation = compile("no-page", noPage);
        assertFalse(noPageCompilation.success());
        assertTrue(noPageCompilation.messages().contains("No concrete Page or API Route classes found"));

        var unmarked = new LinkedHashMap<String, String>();
        unmarked.put("plain.Application", "package plain; public final class Application {}");
        unmarked.put("plain.pages.Page", pageSource("plain.pages", null));
        var unmarkedCompilation = compile("unmarked", unmarked);
        assertTrue(unmarkedCompilation.success(), unmarkedCompilation.messages());
        assertFalse(Files.exists(unmarkedCompilation.classes().resolve("META-INF/roots/routes")));
    }

    @Test
    void publishesAServiceLoadableProcessor() {
        var processor = new RootsRouteProcessor();
        assertNotNull(RootsRouteProcessor.class.getClassLoader()
                .getResource("META-INF/services/javax.annotation.processing.Processor"));
        assertTrue(processor.getSupportedAnnotationTypes().contains("*"));
        assertEquals(SourceVersion.latestSupported(), processor.getSupportedSourceVersion());
    }

    private Compilation compile(String name, Map<String, String> sources) throws IOException {
        var root = temporaryDirectory.resolve(name);
        var sourceDirectory = root.resolve("src");
        var classes = root.resolve("classes");
        Files.createDirectories(sourceDirectory);
        Files.createDirectories(classes);
        var sourceFiles = new ArrayList<Path>();
        for (var entry : sources.entrySet()) {
            var file = sourceDirectory.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            sourceFiles.add(file);
        }

        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            var units = files.getJavaFileObjectsFromPaths(sourceFiles);
            var options = List.of(
                    "--release", Integer.toString(Runtime.version().feature()),
                    "-classpath", System.getProperty("java.class.path")
            );
            var task = compiler.getTask(null, files, diagnostics, options, null, units);
            task.setProcessors(List.of(new RootsRouteProcessor()));
            var success = Boolean.TRUE.equals(task.call());
            var messages = diagnostics.getDiagnostics().stream()
                    .map(diagnostic -> diagnostic.getKind() + ": " + diagnostic.getMessage(Locale.ROOT))
                    .reduce("", (left, right) -> left + right + System.lineSeparator());
            return new Compilation(success, messages, classes);
        }
    }

    private static Map<String, String> completeApplication() {
        var sources = baseApplication();
        sources.put("sample.pages.Layout", layoutSource("sample.pages"));
        sources.put("sample.pages.Page", pageSource("sample.pages", null));
        sources.put("sample.pages.NotFound", notFoundSource());
        sources.put("sample.pages.ErrorPage", errorPageSource());
        sources.put("sample.pages.admin.Layout", layoutSource("sample.pages.admin"));
        sources.put("sample.pages.admin.Page", """
                package sample.pages.admin;
                import com.chaplin.roots.PageContext;
                import com.chaplin.roots.annotation.Authorize;
                import com.chaplin.roots.annotation.Route;
                import com.chaplin.roots.html.Node;
                @Route("/staff/{memberId}/") @Authorize("staff.read")
                public final class Page implements com.chaplin.roots.Page {
                    public Node render(PageContext context) { return com.chaplin.roots.html.Html.main("admin"); }
                }
                """);
        sources.put("sample.api.health.Route", """
                package sample.api.health;
                public final class Route implements com.chaplin.roots.ApiRoute {}
                """);
        return sources;
    }

    private static LinkedHashMap<String, String> baseApplication() {
        var sources = new LinkedHashMap<String, String>();
        sources.put("sample.Application", """
                package sample;
                @com.chaplin.roots.annotation.RootsApplication
                public final class Application {}
                """);
        return sources;
    }

    private static String notFoundSource() {
        return """
                package sample.pages;
                public final class NotFound implements com.chaplin.roots.Page {
                    public com.chaplin.roots.html.Node render(com.chaplin.roots.PageContext context) {
                        return com.chaplin.roots.html.Html.main("missing");
                    }
                }
                """;
    }

    private static String errorPageSource() {
        return """
                package sample.pages;
                public final class ErrorPage implements com.chaplin.roots.Page {
                    public com.chaplin.roots.html.Node render(com.chaplin.roots.PageContext context) {
                        return com.chaplin.roots.html.Html.main("error");
                    }
                }
                """;
    }

    private static String pageSource(String packageName, String route) {
        var annotation = route == null ? "" : "@com.chaplin.roots.annotation.Route(\"" + route + "\")";
        return """
                package %s;
                import com.chaplin.roots.PageContext;
                import com.chaplin.roots.html.Node;
                %s
                public final class Page implements com.chaplin.roots.Page {
                    public Node render(PageContext context) { return com.chaplin.roots.html.Html.main("page"); }
                }
                """.formatted(packageName, annotation);
    }

    private static String authorizedPageSource(String annotation) {
        return """
                package sample.pages;
                import com.chaplin.roots.PageContext;
                import com.chaplin.roots.annotation.Authorize;
                import com.chaplin.roots.html.Node;
                %s
                public final class Page implements com.chaplin.roots.Page {
                    public Node render(PageContext context) { return com.chaplin.roots.html.Html.main("page"); }
                }
                """.formatted(annotation);
    }

    private static String prerenderPageSource(String annotation, String route) {
        var routeAnnotation = route == null ? "" : "@Route(\"" + route + "\")";
        return """
                package sample.pages;
                import com.chaplin.roots.PageContext;
                import com.chaplin.roots.annotation.Prerender;
                import com.chaplin.roots.annotation.Route;
                import com.chaplin.roots.html.Node;
                %s %s
                public final class Page implements com.chaplin.roots.Page {
                    public Node render(PageContext context) { return com.chaplin.roots.html.Html.main("page"); }
                }
                """.formatted(annotation, routeAnnotation);
    }

    private static String pathProviderSource() {
        return """
                package sample;
                public final class ProductPaths implements com.chaplin.roots.StaticPathProvider {
                    public java.util.List<java.util.Map<String, String>> paths() {
                        return java.util.List.of(java.util.Map.of("id", "one"));
                    }
                }
                """;
    }

    private static String authorizedLayoutSource() {
        return """
                package sample.pages;
                @com.chaplin.roots.annotation.Authorize("admin")
                public final class Layout implements com.chaplin.roots.Layout {}
                """;
    }

    private static String layoutSource(String packageName) {
        return """
                package %s;
                import com.chaplin.roots.PageContext;
                import com.chaplin.roots.html.Node;
                public final class Layout implements com.chaplin.roots.Layout {
                    public Node render(PageContext context, Node children) { return children; }
                }
                """.formatted(packageName);
    }

    private static String nestedLayoutSource(String owner) {
        return """
                package sample.pages;
                import com.chaplin.roots.PageContext;
                import com.chaplin.roots.html.Node;
                public final class %s {
                    public static final class Layout implements com.chaplin.roots.Layout {
                        public Node render(PageContext context, Node children) { return children; }
                    }
                }
                """.formatted(owner);
    }

    private record Compilation(boolean success, String messages, Path classes) {
    }
}
