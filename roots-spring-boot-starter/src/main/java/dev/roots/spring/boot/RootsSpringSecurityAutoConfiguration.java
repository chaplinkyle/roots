package dev.roots.spring.boot;

import dev.roots.AuthenticatedIdentity;
import dev.roots.servlet.ServletIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.context.annotation.Bean;

/** Exposes Spring Security authentication to Roots without coupling Roots Core to Spring. */
@AutoConfiguration(after = RootsAutoConfiguration.class)
@ConditionalOnClass({Authentication.class, HttpServletRequest.class, ServletIdentityResolver.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(RootsApplicationDescriptor.class)
@ConditionalOnProperty(prefix = "roots", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "roots", name = "transport", havingValue = "servlet")
public class RootsSpringSecurityAutoConfiguration {
    /** Creates the optional Spring Security bridge auto-configuration. */
    public RootsSpringSecurityAutoConfiguration() {
    }

    /** Resolves authenticated Spring Security names and exact granted authorities.
     * @return Servlet identity resolver
     */
    @Bean
    @ConditionalOnMissingBean(ServletIdentityResolver.class)
    ServletIdentityResolver rootsSpringSecurityIdentityResolver() {
        var containerFallback = ServletIdentityResolver.containerPrincipal();
        return request -> {
            var principal = request.getUserPrincipal();
            if (!(principal instanceof Authentication authentication)) {
                return containerFallback.resolve(request);
            }
            if (!authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(AuthenticatedIdentity.of(
                    authentication.getName(),
                    authentication.getAuthorities().stream()
                            .map(authority -> authority.getAuthority())
                            .filter(authority -> authority != null && !authority.isBlank())
                            .toList()
            ));
        };
    }
}
