package dev.roots;

import dev.roots.internal.RootsServer;

public final class Roots {
    private Roots() {
    }

    public static RunningApplication start(Class<?> applicationClass, String... arguments) {
        return start(RootsConfig.forApplication(applicationClass).arguments(arguments).build());
    }

    public static RunningApplication start(RootsConfig config) {
        var server = new RootsServer(config);
        server.start();
        return new RunningApplication(server);
    }

    public static void run(Class<?> applicationClass, String... arguments) {
        run(RootsConfig.forApplication(applicationClass).arguments(arguments).build());
    }

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
