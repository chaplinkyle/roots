package com.chaplin.roots.spring.boot;

import com.chaplin.roots.AuthenticationProvider;
import com.chaplin.roots.InstanceFactory;
import com.chaplin.roots.LiveViewOwnership;
import com.chaplin.roots.Roots;
import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.RequestBodyPolicy;
import com.chaplin.roots.RequestObserver;
import com.chaplin.roots.ProxyPolicy;
import com.chaplin.roots.RateLimiter;
import com.chaplin.roots.SessionRepository;
import com.chaplin.roots.spring.SpringInstanceFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.SmartInitializingSingleton;

/** Auto-configures a Spring-owned Roots server when {@link EnableRoots} is present. */
@AutoConfiguration
@ConditionalOnClass({Roots.class, ApplicationContext.class})
@ConditionalOnBean(RootsApplicationDescriptor.class)
@ConditionalOnProperty(prefix = "roots", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RootsProperties.class)
public class RootsAutoConfiguration {
    /** Creates the auto-configuration. */
    public RootsAutoConfiguration() {
    }

    @Bean
    @ConditionalOnMissingBean(InstanceFactory.class)
    SpringInstanceFactory rootsInstanceFactory(ApplicationContext applicationContext) {
        return new SpringInstanceFactory(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean(RootsConfig.class)
    RootsConfig rootsConfig(
            RootsApplicationDescriptor descriptor,
            RootsProperties properties,
            InstanceFactory instanceFactory,
            ObjectProvider<SessionRepository> sessionRepositories,
            ObjectProvider<LiveViewOwnership> liveViewOwnershipRegistries,
            ObjectProvider<RequestObserver> requestObservers,
            ObjectProvider<ProxyPolicy> proxyPolicies,
            ObjectProvider<RateLimiter> rateLimiters,
            ObjectProvider<AuthenticationProvider> authenticationProviders,
            ObjectProvider<RootsConfigCustomizer> customizers
    ) {
        var builder = RootsConfig.forApplication(descriptor.applicationClass())
                .host(properties.host())
                .port(properties.port())
                .development(properties.development())
                .viewTimeout(properties.viewTimeout())
                .sessionTimeout(properties.sessionTimeout())
                .maxRequestBytes(properties.maxRequestBytes())
                .requestBodyPolicy(new RequestBodyPolicy(
                        properties.requestBodyMemoryThreshold(),
                        properties.maxMultipartTextFieldBytes(),
                        properties.requestBodyTemporaryDirectory().isBlank()
                                ? RequestBodyPolicy.defaults().temporaryDirectory()
                                : java.nio.file.Path.of(properties.requestBodyTemporaryDirectory())
                ))
                .maxConcurrentRequests(properties.maxConcurrentRequests())
                .maxLiveViews(properties.maxLiveViews())
                .maxSessions(properties.maxSessions())
                .secureCookies(properties.secureCookies())
                .contentSecurityPolicy(properties.contentSecurityPolicy())
                .instanceFactory(instanceFactory);
        var proxyPolicy = proxyPolicies.getIfAvailable();
        builder.proxyPolicy(proxyPolicy == null
                ? ProxyPolicy.trusted(properties.trustedProxies())
                : proxyPolicy);
        var rateLimiter = rateLimiters.getIfAvailable();
        if (rateLimiter != null) {
            builder.rateLimiter(rateLimiter);
        } else if (properties.rateLimitRequests() > 0) {
            builder.rateLimiter(RateLimiter.fixedWindow(
                    properties.rateLimitRequests(),
                    properties.rateLimitWindow(),
                    properties.maxRateLimitClients()
            ));
        }
        var authenticationProvider = authenticationProviders.getIfAvailable();
        if (authenticationProvider != null) {
            builder.authenticationProvider(authenticationProvider);
        }
        var sessionRepository = sessionRepositories.getIfAvailable();
        if (sessionRepository != null) {
            builder.sessionRepository(sessionRepository);
        }
        var liveViewOwnership = liveViewOwnershipRegistries.getIfAvailable();
        if (liveViewOwnership != null) {
            builder.liveViewOwnership(liveViewOwnership);
        }
        builder.observeRequests(new SpringRequestObserverBridge(requestObservers));
        if (properties.pagesPackage() != null && !properties.pagesPackage().isBlank()) {
            builder.pagesPackage(properties.pagesPackage());
        }
        if (properties.apiPackage() != null && !properties.apiPackage().isBlank()) {
            builder.apiPackage(properties.apiPackage());
        }
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean(RootsApplicationLifecycle.class)
    @ConditionalOnProperty(prefix = "roots", name = "transport", havingValue = "jdk", matchIfMissing = true)
    RootsApplicationLifecycle rootsApplicationLifecycle(RootsConfig config, RootsProperties properties) {
        return new RootsApplicationLifecycle(config, properties.shutdownTimeout());
    }

    @Bean
    @ConditionalOnProperty(prefix = "roots", name = "transport", havingValue = "servlet")
    SmartInitializingSingleton rootsServletTransportValidator(ApplicationContext applicationContext) {
        return () -> {
            if (!applicationContext.containsBean("rootsServletRegistration")) {
                throw new IllegalStateException(
                        "roots.transport=servlet requires a Jakarta Servlet web application"
                );
            }
        };
    }
}
