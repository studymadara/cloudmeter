package io.cloudmeter.starter;

import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.ProjectionConfig;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for CloudMeter's Spring Boot integration.
 *
 * <p>Activated when {@code spring.cloudmeter.enabled=true} (the default).
 * Disable with {@code spring.cloudmeter.enabled=false}.
 *
 * <p><strong>WebFlux apps:</strong> installs {@link CloudMeterWebFilter} automatically.
 * No {@code -javaagent} flag required — the filter provides all instrumentation.
 *
 * <p><strong>Spring MVC apps:</strong> the {@code -javaagent} flag is still required
 * for bytecode instrumentation. This starter adds the Actuator endpoint on top.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "spring.cloudmeter.enabled", matchIfMissing = true)
@EnableConfigurationProperties(CloudMeterProperties.class)
public class CloudMeterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MetricsStore cloudMeterMetricsStore(CloudMeterProperties props) {
        MetricsStore store = new MetricsStore(props.getCapacity());
        store.startRecording();
        return store;
    }

    @Bean
    @ConditionalOnMissingBean
    public ProjectionConfig cloudMeterProjectionConfig(CloudMeterProperties props) {
        return ProjectionConfig.builder()
                .provider(CloudProvider.valueOf(props.getProvider().toUpperCase()))
                .region(props.getRegion())
                .targetUsers(props.getTargetUsers())
                .requestsPerUserPerSecond(props.getRequestsPerUserPerSecond())
                .recordingDurationSeconds(60.0)
                .budgetUsd(props.getBudgetUsd())
                .build();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnMissingBean
    public CloudMeterWebFilter cloudMeterWebFilter(MetricsStore store) {
        return new CloudMeterWebFilter(store);
    }

    @Bean
    @ConditionalOnAvailableEndpoint(endpoint = CloudMeterEndpoint.class)
    @ConditionalOnMissingBean
    public CloudMeterEndpoint cloudMeterEndpoint(MetricsStore store, ProjectionConfig config) {
        return new CloudMeterEndpoint(store, config);
    }
}
