package com.chaplin.roots.spring.boot;

import com.chaplin.roots.RequestObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Adds Roots runtime meters when Micrometer is present. */
@AutoConfiguration(
        after = {RootsAutoConfiguration.class, RootsServletAutoConfiguration.class},
        afterName = {
                "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
                "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration"
        }
)
@ConditionalOnClass(MeterBinder.class)
@ConditionalOnBean(RootsRuntime.class)
@ConditionalOnProperty(
        prefix = "management.metrics.enable",
        name = "roots",
        havingValue = "true",
        matchIfMissing = true
)
public class RootsMetricsAutoConfiguration {
    /** Creates the metrics auto-configuration. */
    public RootsMetricsAutoConfiguration() {
    }

    @Bean("rootsMeterBinder")
    @ConditionalOnMissingBean(name = "rootsMeterBinder")
    MeterBinder rootsMeterBinder(RootsRuntime lifecycle) {
        return new RootsMeterBinder(lifecycle);
    }

    @Bean("rootsRequestMetricsObserver")
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(name = "rootsRequestMetricsObserver")
    RequestObserver rootsRequestMetricsObserver(MeterRegistry registry, RootsProperties properties) {
        return new RootsRequestMetricsObserver(registry, properties.transport());
    }
}
