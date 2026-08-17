package dev.roots.internal;

import dev.roots.RootsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConventionRouterManifestTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void prefersAnAnchorSpecificManifestOverClasspathScanning() {
        var config = RootsConfig.forApplication(dev.roots.manifestapp.Application.class).build();
        var router = ConventionRouter.discover(config);

        assertEquals(List.of("PAGE /", "PAGE * (not found)", "PAGE ! (error)", "API  /api/ping"),
                router.routes());
        assertTrue(router.page("/").isPresent());
        assertFalse(router.page("/omitted").isPresent());
        assertEquals(dev.roots.manifestapp.pages.NotFound.class,
                router.notFound().orElseThrow().route().type());
        assertEquals(dev.roots.manifestapp.pages.ErrorPage.class,
                router.errorPage().orElseThrow().route().type());
        assertEquals(List.of(dev.roots.manifestapp.pages.Layout.class),
                router.page("/").orElseThrow().route().layouts());
    }

    @Test
    void fallsBackToScanningWhenConfiguredPackagesDoNotMatch() {
        var config = RootsConfig.forApplication(dev.roots.manifestapp.Application.class)
                .pagesPackage("dev.roots.manifestapp.pages.omitted")
                .build();

        var router = ConventionRouter.discover(config);

        assertTrue(router.page("/").isPresent());
        assertEquals(dev.roots.manifestapp.pages.omitted.Page.class,
                router.page("/").orElseThrow().route().type());
    }

    @Test
    void acceptsLegacyVersionOneManifestsWithoutANotFoundConvention() {
        var config = manifestConfig();
        var legacy = RouteManifest.parse(
                config,
                config.applicationClass().getClassLoader(),
                "legacy.routes",
                manifestHeader() + "\nPAGE\t/\tdev.roots.manifestapp.pages.Page\t\n"
        ).orElseThrow();

        assertEquals(1, legacy.pages().size());
        assertTrue(legacy.notFound().isEmpty());
        assertTrue(legacy.errorPage().isEmpty());
    }

    @Test
    void rejectsMalformedManifestsInsteadOfSilentlyScanning() {
        var config = RootsConfig.forApplication(dev.roots.malformedmanifestapp.Application.class).build();

        var failure = assertThrows(IllegalStateException.class, () -> ConventionRouter.discover(config));
        assertTrue(failure.getMessage().contains("Invalid Roots route manifest"));
        assertTrue(failure.getMessage().contains("unsupported or malformed header"));
    }

    @Test
    void scannerRejectsRoutesThatDifferOnlyByParameterName() {
        var config = RootsConfig.forApplication(dev.roots.ambiguousapp.Application.class).build();

        var failure = assertThrows(IllegalStateException.class, () -> ConventionRouter.discover(config));
        assertTrue(failure.getMessage().contains("Duplicate page"));
        assertTrue(failure.getMessage().contains("/items/"));
    }

    @Test
    void manifestParserRejectsMalformedStructure() {
        var config = manifestConfig();
        var loader = config.applicationClass().getClassLoader();

        assertInvalid(config, loader, "", "missing header");
        assertInvalid(config, loader, "ROOTS_ROUTE_MANIFEST\t1\twrong.Anchor\tx.pages\tx.api\n",
                "application anchor is wrong.Anchor");
        assertInvalid(config, loader, manifestHeader() + "\n\n", "blank route row at line 2");
        assertInvalid(config, loader, manifestHeader() + "\nPAGE\t/\tOnlyThree\n",
                "route row 2 must have four fields");
        assertInvalid(config, loader, manifestHeader() + "\nUNKNOWN\t/\tjava.lang.String\t\n",
                "unknown route kind at line 2");
    }

    @Test
    void manifestParserRejectsInvalidPathsAndClassContracts() {
        var config = manifestConfig();
        var loader = config.applicationClass().getClassLoader();
        var page = "dev.roots.manifestapp.pages.Page";

        assertInvalid(config, loader, manifestHeader() + "\nPAGE\t/trailing/\t" + page + "\t\n",
                "non-canonical path at line 2");
        assertInvalid(config, loader, manifestHeader() + "\nPAGE\t/bad path\t" + page + "\t\n",
                "invalid path at line 2");
        assertInvalid(config, loader, manifestHeader() + "\nPAGE\t/\tmissing.Page\t\n",
                "Could not load route-manifest class missing.Page");
        assertInvalid(config, loader, manifestHeader() + "\nPAGE\t/\tjava.lang.String\t\n",
                "java.lang.String is not a concrete Page");
        assertInvalid(config, loader, manifestHeader() + "\nPAGE\t/\t" + page + "\t,\n",
                "blank layout class name");
        assertInvalid(config, loader, manifestHeader() + "\nAPI\t/api/ping\t"
                        + "dev.roots.manifestapp.api.ping.Route\t" + page + "\n",
                "API route has layouts at line 2");
        assertInvalid(config, loader, manifestHeader() + "\nNOT_FOUND\t/\t" + page + "\t\n",
                "NOT_FOUND requires manifest version 2");
        assertInvalid(config, loader, manifestHeaderV2() + "\nNOT_FOUND\t/elsewhere\t" + page + "\t\n",
                "NOT_FOUND path must be /");
        assertInvalid(config, loader, manifestHeaderV2() + "\nNOT_FOUND\t/\t" + page
                        + "\t\nNOT_FOUND\t/\t" + page + "\t\n",
                "manifest contains multiple NOT_FOUND pages");
        assertInvalid(config, loader, manifestHeaderV2() + "\nERROR_PAGE\t/\t" + page + "\t\n",
                "ERROR_PAGE requires manifest version 3");
        assertInvalid(config, loader, manifestHeaderV3() + "\nERROR_PAGE\t/elsewhere\t" + page + "\t\n",
                "ERROR_PAGE path must be /");
        assertInvalid(config, loader, manifestHeaderV3() + "\nERROR_PAGE\t/\t" + page
                        + "\tdev.roots.manifestapp.pages.Layout\n",
                "ERROR_PAGE cannot declare layouts");
    }

    @Test
    void manifestParserRequiresPagesAndBoundsRouteCount() {
        var config = manifestConfig();
        var loader = config.applicationClass().getClassLoader();
        var apiOnly = manifestHeader() + "\nAPI\t/api/ping\tdev.roots.manifestapp.api.ping.Route\t\n";
        assertInvalid(config, loader, apiOnly, "manifest contains no pages");

        var oversized = new StringBuilder(manifestHeader()).append('\n');
        for (var index = 0; index <= 10_000; index++) {
            oversized.append("PAGE\t/\tdev.roots.manifestapp.pages.Page\t\n");
        }
        assertInvalid(config, loader, oversized.toString(), "manifest exceeds 10000 routes");
    }

    @Test
    void manifestLoaderRejectsDuplicateAndOversizedResources() throws Exception {
        var duplicateName = "META-INF/roots/routes/dev.roots.manifestapp.Application.routes";
        var first = temporaryDirectory.resolve("first");
        var second = temporaryDirectory.resolve("second");
        writeResource(first, duplicateName, manifestHeader());
        writeResource(second, duplicateName, manifestHeader());
        try (var loader = new URLClassLoader(
                new java.net.URL[]{first.toUri().toURL(), second.toUri().toURL()},
                getClass().getClassLoader())) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> RouteManifest.load(manifestConfig(), loader));
            assertTrue(failure.getMessage().contains("Multiple Roots route manifests found"));
        }

        var oversizedConfig = RootsConfig.forApplication(dev.roots.ambiguousapp.Application.class).build();
        var oversizedName = "META-INF/roots/routes/dev.roots.ambiguousapp.Application.routes";
        var oversized = temporaryDirectory.resolve("oversized");
        var file = oversized.resolve(oversizedName);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[1_048_577]);
        try (var loader = new URLClassLoader(
                new java.net.URL[]{oversized.toUri().toURL()}, getClass().getClassLoader())) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> RouteManifest.load(oversizedConfig, loader));
            assertTrue(failure.getMessage().contains("manifest exceeds 1048576 bytes"));
        }
    }

    private static RootsConfig manifestConfig() {
        return RootsConfig.forApplication(dev.roots.manifestapp.Application.class).build();
    }

    private static String manifestHeader() {
        return "ROOTS_ROUTE_MANIFEST\t1\tdev.roots.manifestapp.Application"
                + "\tdev.roots.manifestapp.pages\tdev.roots.manifestapp.api";
    }

    private static String manifestHeaderV2() {
        return "ROOTS_ROUTE_MANIFEST\t2\tdev.roots.manifestapp.Application"
                + "\tdev.roots.manifestapp.pages\tdev.roots.manifestapp.api";
    }

    private static String manifestHeaderV3() {
        return "ROOTS_ROUTE_MANIFEST\t3\tdev.roots.manifestapp.Application"
                + "\tdev.roots.manifestapp.pages\tdev.roots.manifestapp.api";
    }

    private static void assertInvalid(RootsConfig config, ClassLoader loader, String content, String reason) {
        var failure = assertThrows(IllegalStateException.class,
                () -> RouteManifest.parse(config, loader, "test.routes", content));
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
    }

    private static void writeResource(Path root, String name, String content) throws Exception {
        var file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
