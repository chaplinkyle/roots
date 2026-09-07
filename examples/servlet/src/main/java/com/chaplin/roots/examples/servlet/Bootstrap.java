package com.chaplin.roots.examples.servlet;

import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.servlet.RootsServlet;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import java.time.Duration;

/** Programmatic configuration permits the same bounded environment settings as JDK deployments. */
public final class Bootstrap implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent event) {
        var config = RootsConfig.forApplication(Application.class)
                .development(false)
                .secureCookies(true)
                .environment()
                .build();
        var timeout = Duration.parse(System.getenv().getOrDefault("ROOTS_SHUTDOWN_TIMEOUT", "PT30S"));
        if (timeout.isNegative() || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("ROOTS_SHUTDOWN_TIMEOUT must be between zero and one hour");
        }
        var servlet = new RootsServlet(config, timeout);
        var registration = event.getServletContext().addServlet("roots", servlet);
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        registration.addMapping("/*");
    }
}
