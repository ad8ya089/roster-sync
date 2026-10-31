package com.moveinsync.rostersync.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.Banner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RosterPropertiesTest {
    @Test void reconciliationRetentionDurationsAreConfigurable() {
        context.withPropertyValues("roster.retention.recon-actions=2d","roster.retention.uploads=3d").run(application->{
            assertThat(application).hasNotFailed();
            var retention=application.getBean(RosterProperties.class).retention();
            assertThat(retention.reconActions()).isEqualTo(Duration.ofDays(2));
            assertThat(retention.uploads()).isEqualTo(Duration.ofDays(3));
        });
    }
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("roster.security.jwt-secret=test-secret-with-more-than-32-utf8-bytes");

    @Test
    void bindsValidatedDefaultsWithoutAWebServerOrDatabase() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            RosterProperties properties = application.getBean(RosterProperties.class);
            assertThat(properties.reconciliation().buckets()).isEqualTo(256);
            assertThat(properties.reconciliation().brake().maxMissingRatio()).isEqualByComparingTo("0.30");
            assertThat(properties.reconciliation().brake().maxMissingCount()).isNull();
            assertThat(properties.outbox().pollInterval()).isEqualTo(Duration.ofMillis(500));
            assertThat(properties.retention().receipts()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.security().tokenTtl()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.cache().employeeView().ttl()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.ownership().transportOwned()).containsExactlyInAnyOrder("PICKUP_POINT", "SPECIAL_ASSISTANCE");
        });
    }

    @Test
    void bindsShippedYamlWithUnsetOptionalEnvironmentVariables() {
        context.withInitializer(new ConfigDataApplicationContextInitializer()).run(application -> {
            assertThat(application).hasNotFailed();
            RosterProperties properties = application.getBean(RosterProperties.class);
            assertThat(properties.reconciliation().brake().maxMissingCount()).isNull();
            assertThat(properties.reconciliation().inboxDir()).isNull();
        });
    }

    @Test
    void allowsEnvironmentStyleOverrides() {
        context.withPropertyValues("roster.reconciliation.brake.max-missing-ratio=0.5",
                        "roster.reconciliation.brake.max-missing-count=5000", "roster.outbox.poll-interval=1s")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    RosterProperties properties = application.getBean(RosterProperties.class);
                    assertThat(properties.reconciliation().brake().maxMissingRatio()).isEqualByComparingTo("0.5");
                    assertThat(properties.reconciliation().brake().maxMissingCount()).isEqualTo(5000);
                    assertThat(properties.outbox().pollInterval()).isEqualTo(Duration.ofSeconds(1));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo", "prod", "prod,demo"})
    void structuredProfilesDisableThePlainTextBanner(String profiles) {
        context.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profiles)
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(Binder.get(application.getEnvironment()).bind("spring.main.banner-mode", Banner.Mode.class).get())
                            .isEqualTo(Banner.Mode.OFF);
                    assertThat(application.getEnvironment().getProperty("logging.structured.format.console"))
                            .isEqualTo("ecs");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"roster.reconciliation.brake.max-missing-ratio=0",
            "roster.reconciliation.brake.max-missing-ratio=1.01", "roster.reconciliation.brake.max-missing-count=0",
            "roster.reconciliation.buckets=0", "roster.outbox.batch-size=0",
            "roster.outbox.poll-interval=0s", "roster.security.token-ttl=-1s",
            "roster.security.jwt-secret=short", "roster.retention.uploads=0d", "roster.retention.recon-actions=-1d"})
    void rejectsUnsafeConfiguration(String property) {
        context.withPropertyValues(property).run(application -> assertThat(application).hasFailed());
    }

    @Test
    void redactsSecretFromRecordStringRepresentation() {
        context.run(application -> assertThat(application.getBean(RosterProperties.class).toString())
                .doesNotContain("test-secret-with-more-than-32-utf8-bytes"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RosterProperties.class)
    static class PropertiesConfiguration { }
}
