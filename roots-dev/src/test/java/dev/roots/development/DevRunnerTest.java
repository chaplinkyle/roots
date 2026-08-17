package dev.roots.development;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DevRunnerTest {
    @TempDir
    Path temporary;

    @Test
    void parsesProjectAndApplicationArguments() {
        var options = DevRunner.Options.parse(new String[]{
                "--project=fixture", "com.acme.Application", "--", "--port=0", "--production"
        });

        assertEquals(Path.of("fixture").toAbsolutePath().normalize(), options.project());
        assertEquals("com.acme.Application", options.applicationName());
        assertEquals(List.of("--port=0", "--production"), options.applicationArguments());
        assertThrows(IllegalArgumentException.class, () -> DevRunner.Options.parse(new String[0]));
        assertThrows(IllegalArgumentException.class,
                () -> DevRunner.Options.parse(new String[]{"one.Application", "two.Application"}));
    }

    @Test
    void loadsApplicationClassesFreshButDelegatesFrameworkTypes() throws Exception {
        var testClasses = Path.of(sample.Reloadable.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var first = loader(testClasses.toUri().toURL());
             var second = loader(testClasses.toUri().toURL())) {
            var firstType = Class.forName("sample.Reloadable", true, first);
            var secondType = Class.forName("sample.Reloadable", true, second);

            assertNotEquals(sample.Reloadable.class, firstType);
            assertNotEquals(firstType, secondType);
            assertEquals(dev.roots.Roots.class, first.loadClass("dev.roots.Roots"));
            assertEquals(firstType.getResource("/sample/Reloadable.class"),
                    first.getResource("sample/Reloadable.class"));
        }
    }

    @Test
    void watchesNewAndModifiedSourceDirectories() throws Exception {
        var source = Files.createDirectories(temporary.resolve("src/main/java/sample"));
        try (var watcher = new DevRunner.SourceWatcher(List.of(temporary.resolve("src/main/java")))) {
            var changed = CompletableFuture.supplyAsync(() -> {
                try {
                    return watcher.awaitChange();
                } catch (InterruptedException exception) {
                    throw new IllegalStateException(exception);
                }
            });
            Files.writeString(source.resolve("Changed.java"), "package sample; final class Changed {}");
            assertTrue(changed.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void watcherCloseUnblocksWaitAndMissingRootsFailFast() throws Exception {
        var source = Files.createDirectories(temporary.resolve("src/main/java"));
        var watcher = new DevRunner.SourceWatcher(List.of(source));
        var waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return watcher.awaitChange();
            } catch (InterruptedException exception) {
                throw new IllegalStateException(exception);
            }
        });
        watcher.close();
        assertEquals(false, waiting.get(3, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class,
                () -> new DevRunner.SourceWatcher(List.of(temporary.resolve("absent"))));
    }

    @Test
    void selectsAPlatformMavenCompileCommand() {
        var command = DevRunner.MavenCompiler.command(temporary);
        assertTrue(command.contains("-DskipTests"));
        assertTrue(command.contains("clean"));
        assertTrue(command.contains("compile"));
        assertTrue(command.stream().anyMatch(part -> part.contains("mvn")));
    }

    @Test
    void keepsLastGoodServerOnCompileFailureAndRestartsOnSuccess() throws Exception {
        var project = temporary.resolve("application");
        var sources = Files.createDirectories(project.resolve("src/main/java/fixture"));
        var target = Files.createDirectories(project.resolve("target/classes"));
        copyFixtureClasses(target);
        writeFakeWrapper(project);
        var capture = new ByteArrayOutputStream();
        var options = new DevRunner.Options(project, "fixture.Application", List.of("--port=0"));

        try (var session = new DevRunner.Session(options, new PrintStream(capture, true, StandardCharsets.UTF_8))) {
            var running = CompletableFuture.runAsync(() -> {
                try {
                    session.run();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            waitUntil(() -> capture.toString(StandardCharsets.UTF_8).contains("development server running"));
            var serverUri = URI.create(capture.toString(StandardCharsets.UTF_8)
                    .lines().filter(line -> line.startsWith("Roots development server running at "))
                    .findFirst().orElseThrow().substring("Roots development server running at ".length()));
            var initialVersion = dev.roots.internal.DevelopmentEvents.current("fixture.Application").version();

            Files.writeString(project.resolve("fail.flag"), "fail");
            Files.writeString(sources.resolve("Trigger.java"), "package fixture; final class Trigger {}");
            waitUntil(() -> dev.roots.internal.DevelopmentEvents.current("fixture.Application").version() > initialVersion);
            var failure = dev.roots.internal.DevelopmentEvents.current("fixture.Application");
            assertEquals(dev.roots.internal.DevelopmentEvents.Kind.FAILURE, failure.kind());
            assertEquals(200, health(serverUri));

            Files.delete(project.resolve("fail.flag"));
            Files.writeString(sources.resolve("Trigger.java"), "package fixture; final class Trigger { int value; }");
            waitUntil(() -> dev.roots.internal.DevelopmentEvents.current("fixture.Application").version()
                    > failure.version());
            assertEquals(dev.roots.internal.DevelopmentEvents.Kind.RELOAD,
                    dev.roots.internal.DevelopmentEvents.current("fixture.Application").kind());
            assertEquals(200, health(serverUri));

            session.close();
            running.get(5, TimeUnit.SECONDS);
        }
    }

    private static DevRunner.ApplicationClassLoader loader(URL classes) {
        return new DevRunner.ApplicationClassLoader(classes, DevRunner.class.getClassLoader(), "sample.");
    }

    private static int health(URI serverUri) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(serverUri.resolve("/_roots/health")).GET().build(),
                HttpResponse.BodyHandlers.discarding()
        ).statusCode();
    }

    private static void copyFixtureClasses(Path target) throws Exception {
        var testClasses = Path.of(fixture.Application.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var fixture = testClasses.resolve("fixture");
        try (var paths = Files.walk(fixture)) {
            for (var path : paths.toList()) {
                var destination = target.resolve(testClasses.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private static void writeFakeWrapper(Path project) throws Exception {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Files.writeString(project.resolve("mvnw.cmd"), """
                    @echo off
                    if exist fail.flag (
                      echo simulated compiler failure
                      exit /b 1
                    )
                    exit /b 0
                    """);
            return;
        }
        var wrapper = project.resolve("mvnw");
        Files.writeString(wrapper, """
                #!/bin/sh
                if [ -f fail.flag ]; then
                  echo simulated compiler failure
                  exit 1
                fi
                exit 0
                """);
        assertTrue(wrapper.toFile().setExecutable(true));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Condition was not satisfied before timeout");
            }
            Thread.sleep(20);
        }
    }
}
