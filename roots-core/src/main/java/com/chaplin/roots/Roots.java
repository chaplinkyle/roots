package com.chaplin.roots;

import com.chaplin.roots.internal.RootsServer;
import java.time.Duration;
import java.util.Objects;

/** Entry points for starting a Roots application. */
public final class Roots {
    /**
     * Browser/server wire-protocol version used by this framework build.
     *
     * <p>Applications normally do not send the protocol themselves. The value is
     * public so deployment tooling, adapters, and compatibility diagnostics can
     * identify the contract spoken by the bundled browser driver.</p>
     */
    public static final String PROTOCOL_VERSION = "1";

    private Roots() {
    }

    /** Starts an application discovered from its marker class.
     * @param applicationClass application marker class
     * @param arguments command-line arguments
     * @return running application handle */
    public static RunningApplication start(Class<?> applicationClass, String... arguments) {
        return start(RootsConfig.forApplication(applicationClass).environment().arguments(arguments).build());
    }

    /** Starts an application with explicit configuration.
     * @param config validated configuration
     * @return running application handle */
    public static RunningApplication start(RootsConfig config) {
        var server = new RootsServer(config);
        try {
            server.start();
            return new RunningApplication(server);
        } catch (RuntimeException | Error failure) {
            try {
                server.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** Starts and blocks on an application discovered from its marker class.
     * @param applicationClass application marker class
     * @param arguments command-line arguments */
    public static void run(Class<?> applicationClass, String... arguments) {
        run(RootsConfig.forApplication(applicationClass).environment().arguments(arguments).build());
    }

    /** Starts and blocks on an explicitly configured application.
     * Shutdown drains for 30 seconds, or the ISO-8601 {@code ROOTS_SHUTDOWN_TIMEOUT}
     * environment value. Use the duration overload to choose the bound explicitly.
     * @param config validated configuration */
    public static void run(RootsConfig config) {
        var configured = System.getenv("ROOTS_SHUTDOWN_TIMEOUT");
        final Duration timeout;
        try {
            timeout = configured == null ? Duration.ofSeconds(30) : Duration.parse(configured);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid deployment setting ROOTS_SHUTDOWN_TIMEOUT");
        }
        run(config, timeout);
    }

    /**
     * Starts and blocks, gracefully draining on process shutdown or interruption.
     * The process supervisor must allow at least this long before forcibly killing
     * the JVM. Forced termination cannot run Java shutdown hooks.
     *
     * @param config validated configuration
     * @param shutdownTimeout maximum drain time, from zero through one hour
     */
    public static void run(RootsConfig config, Duration shutdownTimeout) {
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative() || shutdownTimeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Shutdown timeout must be between zero and one hour");
        }
        var application = start(config);
        var shutdown = Thread.ofPlatform()
                .name("roots-shutdown")
                .unstarted(() -> application.closeGracefully(shutdownTimeout));
        Runtime.getRuntime().addShutdownHook(shutdown);
        System.out.println("Roots running at " + application.uri());
        if (config.development()) {
            application.routes().forEach(route -> System.out.println("  " + route));
        }
        try {
            application.await();
        } catch (InterruptedException exception) {
            // Awaiting request completion must not inherit this interruption.
            application.closeGracefully(shutdownTimeout);
            Thread.currentThread().interrupt();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException shuttingDown) {
                // The JVM is already running the registered hook.
            }
        }
    }
}
