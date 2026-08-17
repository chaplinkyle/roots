package dev.roots.spring.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/** Adds a {@code roots} health contributor when Spring Boot Actuator is present. */
@AutoConfiguration(after = {RootsAutoConfiguration.class, RootsServletAutoConfiguration.class})
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(RootsRuntime.class)
@ConditionalOnEnabledHealthIndicator("roots")
public class RootsHealthAutoConfiguration {
    /** Creates the health auto-configuration. */
    public RootsHealthAutoConfiguration() {
    }

    @Bean("rootsHealthIndicator")
    @ConditionalOnMissingBean(name = "rootsHealthIndicator")
    HealthIndicator rootsHealthIndicator(RootsRuntime lifecycle) {
        return new RootsHealthIndicator(lifecycle);
    }
}
