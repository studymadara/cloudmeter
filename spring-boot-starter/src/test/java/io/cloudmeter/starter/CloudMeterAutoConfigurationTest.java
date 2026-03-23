package io.cloudmeter.starter;

import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.costengine.ProjectionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CloudMeterAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner runner =
            new ReactiveWebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(CloudMeterAutoConfiguration.class));

    @Test
    void autoConfiguration_createsMetricsStore() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(MetricsStore.class));
    }

    @Test
    void autoConfiguration_createsProjectionConfig() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(ProjectionConfig.class));
    }

    @Test
    void autoConfiguration_createsWebFilter() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(CloudMeterWebFilter.class));
    }

    @Test
    void autoConfiguration_disabled_whenPropertyFalse() {
        runner.withPropertyValues("spring.cloudmeter.enabled=false")
              .run(ctx -> assertThat(ctx).doesNotHaveBean(MetricsStore.class));
    }

    @Test
    void autoConfiguration_respectsCustomProvider() {
        runner.withPropertyValues("spring.cloudmeter.provider=GCP", "spring.cloudmeter.region=us-central1")
              .run(ctx -> {
                  ProjectionConfig cfg = ctx.getBean(ProjectionConfig.class);
                  assertThat(cfg.getProvider().name()).isEqualTo("GCP");
              });
    }
}
