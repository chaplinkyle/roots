package com.chaplin.roots.spring.boot;

import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.servlet.RootsServlet;
import com.chaplin.roots.servlet.ServletIdentityResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

/** Registers Roots in Spring Boot's existing Jakarta Servlet web server. */
@AutoConfiguration(after = {RootsAutoConfiguration.class, RootsSpringSecurityAutoConfiguration.class})
@ConditionalOnClass({RootsServlet.class, ServletRegistrationBean.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean({RootsApplicationDescriptor.class, RootsConfig.class})
@ConditionalOnProperty(prefix = "roots", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "roots", name = "transport", havingValue = "servlet")
public class RootsServletAutoConfiguration {
    /** Creates the Servlet transport auto-configuration. */
    public RootsServletAutoConfiguration() {
    }

    @Bean
    @ConditionalOnMissingBean(RootsServlet.class)
    RootsServlet rootsServlet(
            RootsConfig config,
            RootsProperties properties,
            ObjectProvider<ServletIdentityResolver> identityResolvers
    ) {
        var identityResolver = identityResolvers.getIfAvailable(ServletIdentityResolver::containerPrincipal);
        return new RootsServlet(config, properties.shutdownTimeout(), identityResolver);
    }

    @Bean
    @ConditionalOnMissingBean(name = "rootsServletRegistration")
    ServletRegistrationBean<RootsServlet> rootsServletRegistration(
            RootsServlet servlet,
            RootsProperties properties
    ) {
        var mapping = properties.servletPath().equals("/")
                ? "/*"
                : properties.servletPath() + "/*";
        var registration = new ServletRegistrationBean<>(servlet, mapping);
        registration.setName("roots");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(RootsRuntime.class)
    RootsRuntime rootsServletRuntime(RootsServlet servlet, RootsConfig config) {
        return new RootsServletRuntime(servlet, config);
    }

    @Bean
    RootsServletLifecycle rootsServletLifecycle(RootsServlet servlet) {
        return new RootsServletLifecycle(servlet);
    }
}
