package dev.roots;

import dev.roots.internal.RootsServer;

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
        return start(RootsConfig.forApplication(applicationClass).arguments(arguments).build());
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
        run(RootsConfig.forApplication(applicationClass).arguments(arguments).build());
    }

    /** Starts and blocks on an explicitly configured application.
     * @param config validated configuration */
    public static void run(RootsConfig config) {
        var application = start(config);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform()
                .name("roots-shutdown")
                .unstarted(application::close));
        System.out.println("Roots running at " + application.uri());
        if (config.development()) {
            application.routes().forEach(route -> System.out.println("  " + route));
        }
        try {
            application.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            application.close();
        }
    }
}
