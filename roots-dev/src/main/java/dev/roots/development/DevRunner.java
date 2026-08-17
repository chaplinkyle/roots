package dev.roots.development;

import dev.roots.Roots;
import dev.roots.RootsConfig;
import dev.roots.RunningApplication;
import dev.roots.internal.DevelopmentEvents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches a conventional Maven application, recompiles changed sources, and restarts it in an
 * isolated application class loader while keeping browser state informed.
 */
public final class DevRunner {
    private DevRunner() {
    }

    /** Runs the development loop.
     * The first argument is the application class. Arguments after {@code --} are passed to
     * application configuration.
     * @param arguments runner and application arguments
     * @throws Exception if the initial application cannot be compiled or started */
    public static void main(String[] arguments) throws Exception {
        var options = Options.parse(arguments);
        try (var session = new Session(options, System.out)) {
            var shutdown = Thread.ofPlatform().name("roots-dev-shutdown").unstarted(session::close);
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                session.run();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                    // The JVM is already running shutdown hooks.
                }
            }
        }
    }

    record Options(Path project, String applicationName, List<String> applicationArguments) {
        Options {
            project = project.toAbsolutePath().normalize();
            if (applicationName.isBlank()) {
                throw new IllegalArgumentException("Application class must not be blank");
            }
            applicationArguments = List.copyOf(applicationArguments);
        }

        static Options parse(String[] arguments) {
            Objects.requireNonNull(arguments, "arguments");
            if (arguments.length == 0) {
                throw new IllegalArgumentException(
                        "Usage: DevRunner [--project=<directory>] <application-class> [-- <application-options>]"
                );
            }
            var project = Path.of(System.getProperty("user.dir"));
            String applicationName = null;
            var applicationArguments = new ArrayList<String>();
            var passthrough = false;
            for (var argument : arguments) {
                if (passthrough) {
                    applicationArguments.add(argument);
                } else if (argument.equals("--")) {
                    passthrough = true;
                } else if (argument.startsWith("--project=")) {
                    project = Path.of(argument.substring("--project=".length()));
                } else if (applicationName == null) {
                    applicationName = argument;
                } else {
                    throw new IllegalArgumentException("Unexpected development runner argument: " + argument);
                }
            }
            if (applicationName == null) {
                throw new IllegalArgumentException("An application class is required");
            }
            return new Options(project, applicationName, applicationArguments);
        }
    }

    static final class Session implements AutoCloseable {
        private static final Duration RESTART_DRAIN = Duration.ofSeconds(2);
        private final Options options;
        private final PrintStream output;
        private final MavenCompiler compiler;
        private final SourceWatcher watcher;
        private final Path classes;
        private Path lastGood;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Generation current;
        private int port;

        Session(Options options, PrintStream output) throws IOException {
            this.options = options;
            this.output = output;
            classes = options.project().resolve("target/classes");
            lastGood = Files.createTempDirectory("roots-last-good-");
            compiler = new MavenCompiler(options.project());
            watcher = new SourceWatcher(List.of(
                    options.project().resolve("src/main/java"),
                    options.project().resolve("src/main/resources")
            ));
        }

        void run() throws Exception {
            var result = compiler.compile();
            if (!result.success()) {
                throw new IllegalStateException("Initial Roots compilation failed:\n" + result.output());
            }
            snapshot(classes, lastGood);
            current = Generation.prepare(lastGood, options);
            current.start(null);
            port = current.application().uri().getPort();
            output.println("Roots development server running at " + current.application().uri());
            output.println("Watching src/main/java and src/main/resources (press Ctrl+C to stop)");

            while (!closed.get()) {
                if (!watcher.awaitChange()) {
                    return;
                }
                rebuild();
            }
        }

        private void rebuild() {
            output.println("Roots detected a change; compiling...");
            var result = compiler.compile();
            if (!result.success()) {
                output.println(result.output());
                DevelopmentEvents.failure(options.applicationName(), result.output());
                return;
            }
            Generation candidate = null;
            Path candidateSnapshot = null;
            var currentStopped = false;
            try {
                candidateSnapshot = Files.createTempDirectory("roots-generation-");
                snapshot(classes, candidateSnapshot);
                candidate = Generation.prepare(candidateSnapshot, options);
                current.closeGracefully(RESTART_DRAIN);
                currentStopped = true;
                candidate.start(port);
                current = candidate;
                candidate = null;
                var previousSnapshot = lastGood;
                lastGood = candidateSnapshot;
                candidateSnapshot = null;
                deleteTree(previousSnapshot);
                DevelopmentEvents.reload(options.applicationName());
                output.println("Roots reloaded " + current.application().uri());
            } catch (Throwable failure) {
                if (candidate != null) {
                    candidate.close();
                }
                deleteTree(candidateSnapshot);
                var diagnostic = diagnostic("Application restart failed", failure);
                output.println(diagnostic);
                if (currentStopped) {
                    recoverLastGood(diagnostic);
                } else {
                    DevelopmentEvents.failure(options.applicationName(), diagnostic);
                }
            }
        }

        private void recoverLastGood(String diagnostic) {
            try {
                current = Generation.prepare(lastGood, options);
                current.start(port);
                DevelopmentEvents.failure(options.applicationName(), diagnostic);
            } catch (Throwable recoveryFailure) {
                recoveryFailure.addSuppressed(new IllegalStateException(diagnostic));
                throw new IllegalStateException("Roots could not restore the last good application", recoveryFailure);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            compiler.close();
            watcher.close();
            if (current != null) {
                current.close();
            }
            deleteTree(lastGood);
        }
    }

    static final class Generation implements AutoCloseable {
        private final ApplicationClassLoader loader;
        private final RootsConfig baseConfig;
        private RunningApplication application;

        private Generation(ApplicationClassLoader loader, RootsConfig baseConfig) {
            this.loader = loader;
            this.baseConfig = baseConfig;
        }

        static Generation prepare(Path classes, Options options) throws Exception {
            var separator = options.applicationName().lastIndexOf('.');
            var applicationPackage = separator < 0 ? "" : options.applicationName().substring(0, separator + 1);
            var loader = new ApplicationClassLoader(
                    classes.toUri().toURL(),
                    DevRunner.class.getClassLoader(),
                    applicationPackage
            );
            try {
                var applicationClass = Class.forName(options.applicationName(), true, loader);
                var config = invokeConfig(applicationClass, options.applicationArguments());
                return new Generation(loader, config);
            } catch (Throwable failure) {
                loader.close();
                throw failure;
            }
        }

        void start(Integer fixedPort) {
            var config = developmentConfig(baseConfig, fixedPort == null ? baseConfig.port() : fixedPort);
            application = withContext(loader, () -> Roots.start(config));
        }

        RunningApplication application() {
            if (application == null) {
                throw new IllegalStateException("Application generation has not started");
            }
            return application;
        }

        void closeGracefully(Duration timeout) {
            if (application != null) {
                application.closeGracefully(timeout);
                application = null;
            }
            closeLoader();
        }

        @Override
        public void close() {
            if (application != null) {
                application.close();
                application = null;
            }
            closeLoader();
        }

        private void closeLoader() {
            try {
                loader.close();
            } catch (IOException exception) {
                throw new IllegalStateException("Could not close the application class loader", exception);
            }
        }
    }

    static final class ApplicationClassLoader extends URLClassLoader {
        private final String applicationPackage;

        ApplicationClassLoader(URL classes, ClassLoader parent, String applicationPackage) {
            super(new URL[]{classes}, parent);
            this.applicationPackage = applicationPackage;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);
                if (loaded == null && isApplicationClass(name)) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        // Generated and optional application-package types may still come from a dependency.
                    }
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        @Override
        public URL getResource(String name) {
            var local = findResource(name);
            return local == null ? super.getResource(name) : local;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            var local = Collections.list(findResources(name));
            if (name.startsWith("META-INF/roots/routes/") && !local.isEmpty()) {
                return Collections.enumeration(local);
            }
            var all = new ArrayList<>(local);
            var seen = new HashSet<>(local);
            for (var resource : Collections.list(getParent().getResources(name))) {
                if (seen.add(resource)) {
                    all.add(resource);
                }
            }
            return Collections.enumeration(all);
        }

        private boolean isApplicationClass(String name) {
            return applicationPackage.isEmpty() || name.startsWith(applicationPackage);
        }
    }

    static final class MavenCompiler implements AutoCloseable {
        private static final int MAXIMUM_OUTPUT_BYTES = 32 * 1024;
        private final Path project;
        private volatile Process process;

        MavenCompiler(Path project) {
            this.project = project;
        }

        CompileResult compile() {
            var capture = new ByteArrayOutputStream();
            try {
                var command = command(project);
                process = new ProcessBuilder(command)
                        .directory(project.toFile())
                        .redirectErrorStream(true)
                        .start();
                try (var input = process.getInputStream()) {
                    var buffer = new byte[8_192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            capture.write(buffer, 0, read);
                            trim(capture);
                        }
                    }
                }
                var exit = process.waitFor();
                return new CompileResult(exit == 0, capture.toString(Charset.defaultCharset()).strip());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new CompileResult(false, "Compilation was interrupted");
            } catch (IOException exception) {
                return new CompileResult(false, diagnostic("Could not launch Maven compilation", exception));
            } finally {
                process = null;
            }
        }

        @Override
        public void close() {
            var running = process;
            if (running != null) {
                running.destroy();
                try {
                    if (!running.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        running.destroyForcibly();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    running.destroyForcibly();
                }
            }
        }

        static List<String> command(Path project) {
            var windows = System.getProperty("os.name").toLowerCase().contains("win");
            var mavenHome = System.getProperty("maven.home");
            if (windows) {
                var wrapper = project.resolve("mvnw.cmd");
                var installed = mavenHome == null ? null : Path.of(mavenHome).resolve("bin/mvn.cmd");
                var executable = Files.isRegularFile(wrapper)
                        ? wrapper.toString()
                        : installed != null && Files.isRegularFile(installed) ? installed.toString() : "mvn.cmd";
                return List.of("cmd.exe", "/d", "/c", executable, "-q", "-DskipTests", "clean", "compile");
            }
            var wrapper = project.resolve("mvnw");
            var installed = mavenHome == null ? null : Path.of(mavenHome).resolve("bin/mvn");
            var executable = Files.isRegularFile(wrapper)
                    ? wrapper.toString()
                    : installed != null && Files.isRegularFile(installed) ? installed.toString() : "mvn";
            return List.of(executable, "-q", "-DskipTests", "clean", "compile");
        }

        private static void trim(ByteArrayOutputStream capture) {
            if (capture.size() <= MAXIMUM_OUTPUT_BYTES) {
                return;
            }
            var bytes = capture.toByteArray();
            capture.reset();
            capture.write(bytes, bytes.length - MAXIMUM_OUTPUT_BYTES, MAXIMUM_OUTPUT_BYTES);
        }
    }

    record CompileResult(boolean success, String output) {
    }

    static final class SourceWatcher implements AutoCloseable {
        private final WatchService service;
        private final Set<Path> roots = new HashSet<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        SourceWatcher(List<Path> candidates) throws IOException {
            service = FileSystems.getDefault().newWatchService();
            for (var candidate : candidates) {
                if (Files.isDirectory(candidate)) {
                    var root = candidate.toAbsolutePath().normalize();
                    roots.add(root);
                    registerTree(root);
                }
            }
            if (roots.isEmpty()) {
                service.close();
                throw new IllegalArgumentException("No src/main/java or src/main/resources directory exists");
            }
        }

        boolean awaitChange() throws InterruptedException {
            try {
                var key = service.take();
                consume(key);
                Thread.sleep(150);
                while ((key = service.poll()) != null) {
                    consume(key);
                }
                return true;
            } catch (ClosedWatchServiceException exception) {
                return false;
            }
        }

        private void consume(WatchKey key) {
            var directory = (Path) key.watchable();
            for (var event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                var changed = directory.resolve((Path) event.context());
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                    try {
                        registerTree(changed);
                    } catch (IOException ignored) {
                        // A quickly removed directory will be represented by the current rebuild.
                    }
                }
            }
            key.reset();
        }

        private void registerTree(Path root) throws IOException {
            try (var directories = Files.walk(root)) {
                for (var directory : directories.filter(Files::isDirectory).toList()) {
                    directory.register(
                            service,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE
                    );
                }
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    service.close();
                } catch (IOException ignored) {
                    // Closing a watcher is best effort during shutdown.
                }
            }
        }
    }

    private static RootsConfig invokeConfig(Class<?> applicationClass, List<String> arguments) throws Exception {
        try {
            var method = applicationClass.getDeclaredMethod("config", String[].class);
            if (!Modifier.isStatic(method.getModifiers()) || !RootsConfig.class.isAssignableFrom(method.getReturnType())) {
                throw new IllegalStateException("Application config(String...) must be static and return RootsConfig");
            }
            if (!method.trySetAccessible()) {
                throw new IllegalStateException("Application config(String...) is not accessible");
            }
            try {
                return (RootsConfig) method.invoke(null, (Object) arguments.toArray(String[]::new));
            } catch (InvocationTargetException exception) {
                var cause = exception.getCause();
                if (cause instanceof Exception checked) {
                    throw checked;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw exception;
            }
        } catch (NoSuchMethodException ignored) {
            return RootsConfig.forApplication(applicationClass)
                    .arguments(arguments.toArray(String[]::new))
                    .build();
        }
    }

    private static RootsConfig developmentConfig(RootsConfig config, int port) {
        return new RootsConfig(
                config.applicationClass(), config.pagesPackage(), config.apiPackage(), config.host(), port, true,
                config.viewTimeout(), config.sessionTimeout(), config.maxRequestBytes(), config.maxConcurrentRequests(),
                config.maxLiveViews(), config.maxSessions(), config.secureCookies(), config.instanceFactory(),
                config.authorizationPolicies(), config.middleware(), config.exceptionMappers(), config.cache(),
                config.contentSecurityPolicy(), config.sessionRepository(), config.requestObservers()
        );
    }

    private static <T> T withContext(ClassLoader loader, ThrowingSupplier<T> supplier) {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return supplier.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void snapshot(Path source, Path destination) throws IOException {
        deleteContents(destination);
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteContents(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Collections.reverseOrder()).toList()) {
                if (!path.equals(directory)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void deleteTree(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            deleteContents(directory);
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // Temporary last-good snapshots are best-effort cleanup at process exit.
        }
    }

    private static String diagnostic(String summary, Throwable failure) {
        var detail = failure.getMessage();
        return summary + ": " + (detail == null || detail.isBlank() ? failure.getClass().getName() : detail);
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }
}
